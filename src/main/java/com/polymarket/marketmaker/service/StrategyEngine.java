package com.polymarket.marketmaker.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.polymarket.marketmaker.model.Order;
import com.polymarket.marketmaker.model.OrderBook;

/**
 * The market-making strategy engine.
 *
 * <p>
 * Runs a 10 Hz evaluation loop that reads the live {@link OrderBook},
 * computes the spread, and generates two-sided quotes (BUY slightly above
 * best bid, SELL slightly below best ask) when the spread is wide enough.
 * </p>
 *
 * <h3>Thread safety</h3>
 * <p>
 * {@code @Scheduled} runs on Spring's single scheduler thread.
 * {@code OrderBook} is backed by
 * {@link java.util.concurrent.ConcurrentSkipListMap},
 * so {@code getBestBid()} and {@code getBestAsk()} are lock-free.
 * The two reads are NOT an atomic snapshot — the spread may be off by one tick
 * if the WS thread updates between them — but that is acceptable for our
 * quoting latency budget (see implementation plan for full analysis).
 * </p>
 *
 * <h3>Paper-trading guard</h3>
 * <p>
 * When {@code paper-trading-enabled = true} (default), the engine logs
 * intended trades to the console and <b>never calls the CLOB API</b>.
 * Flip to {@code false} only when you are ready to trade real funds.
 * </p>
 *
 * <h3>Market switching</h3>
 * <p>
 * Implements {@link MarketSwitchListener}. When
 * {@link MarketDiscoveryService} detects a new active market, it calls
 * {@link #onMarketSwitch(String)} which pauses quoting, clears the book,
 * swaps the token ID, and resumes — all via {@code volatile} fields.
 * </p>
 */
@Service
public class StrategyEngine implements MarketSwitchListener {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    /** One tick increment / decrement for quote pricing. */
    private static final BigDecimal TICK = new BigDecimal("0.01");

    private final OrderBook orderBook;
    private final OrderManagementService orderManagementService;
    private final BigDecimal targetSpreadCents;
    private final BigDecimal orderSize;
    private final boolean paperTradingEnabled;

    /** Current market token ID — swapped atomically on market switch. */
    private volatile String marketId;

    /** When true the 10 Hz loop returns immediately without quoting. */
    private volatile boolean paused = false;

    public StrategyEngine(
            OrderBook orderBook,
            OrderManagementService orderManagementService,
            @Value("${polymarket.market-id}") String marketId,
            @Value("${polymarket.strategy.target-spread-cents:0.02}") BigDecimal targetSpreadCents,
            @Value("${polymarket.strategy.order-size:100}") BigDecimal orderSize,
            @Value("${polymarket.strategy.paper-trading-enabled:true}") boolean paperTradingEnabled) {
        this.orderBook = orderBook;
        this.orderManagementService = orderManagementService;
        this.marketId = marketId;
        this.targetSpreadCents = targetSpreadCents;
        this.orderSize = orderSize;
        this.paperTradingEnabled = paperTradingEnabled;

        log.info("⚙ StrategyEngine initialized — marketId={}, targetSpread={}, orderSize={}, paperTrading={}",
                marketId, targetSpreadCents, orderSize, paperTradingEnabled);
    }

    // -----------------------------------------------------------------
    // MarketSwitchListener — safe transition protocol
    // -----------------------------------------------------------------

    /**
     * Called by {@link MarketDiscoveryService} when a new token is discovered.
     *
     * <ol>
     * <li>Pause quoting immediately</li>
     * <li>Clear the stale order book</li>
     * <li>Swap to the new token ID</li>
     * <li>Resume quoting</li>
     * </ol>
     */
    @Override
    public void onMarketSwitch(String newTokenId) {
        log.info("⏸ StrategyEngine pausing for market switch → {}", newTokenId);
        this.paused = true;

        // Clear stale data
        orderBook.clear();

        // Swap token ID
        this.marketId = newTokenId;

        // Resume
        this.paused = false;
        log.info("▶ StrategyEngine resumed with new marketId={}", newTokenId);
    }

    // -----------------------------------------------------------------
    // Scheduled evaluation loop — 10 Hz
    // -----------------------------------------------------------------

    /**
     * Core strategy tick. Runs every 100ms (10×/sec).
     *
     * <ol>
     * <li>Check paused flag — return immediately if paused</li>
     * <li>Read best bid and best ask (lock-free, weakly consistent)</li>
     * <li>Skip if either side is empty (book not populated yet)</li>
     * <li>Compute spread; skip if tighter than target</li>
     * <li>Generate a BUY at bestBid + 1 tick, SELL at bestAsk − 1 tick</li>
     * <li>Paper-trade guard: log or execute</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 100)
    public void evaluateAndQuote() {
        // 0. Pause guard — market switch in progress
        if (paused) {
            log.trace("⏸ Strategy paused — skipping tick");
            return;
        }

        // 1. Lock-free read of both sides
        Optional<Map.Entry<BigDecimal, BigDecimal>> bestBidOpt = orderBook.getBestBid();
        Optional<Map.Entry<BigDecimal, BigDecimal>> bestAskOpt = orderBook.getBestAsk();

        // 2. Guard: book must have both sides
        if (bestBidOpt.isEmpty() || bestAskOpt.isEmpty()) {
            log.trace("📖 Book empty — skipping (bids={}, asks={})",
                    orderBook.getBidDepth(), orderBook.getAskDepth());
            return;
        }

        BigDecimal bestBidPrice = bestBidOpt.get().getKey();
        BigDecimal bestAskPrice = bestAskOpt.get().getKey();

        // 3. Compute spread
        BigDecimal spread = bestAskPrice.subtract(bestBidPrice);

        if (spread.compareTo(targetSpreadCents) < 0) {
            log.trace("📏 Spread too tight ({} < {}) — skipping",
                    spread.toPlainString(), targetSpreadCents.toPlainString());
            return;
        }

        // 4. Generate quote prices — one tick inside the spread
        BigDecimal buyPrice = bestBidPrice.add(TICK);
        BigDecimal sellPrice = bestAskPrice.subtract(TICK);

        // 5. Build orders
        Order buyOrder = Order.builder()
                .tokenId(marketId)
                .side(Order.Side.BUY)
                .price(buyPrice)
                .size(orderSize)
                .build();

        Order sellOrder = Order.builder()
                .tokenId(marketId)
                .side(Order.Side.SELL)
                .price(sellPrice)
                .size(orderSize)
                .build();

        // 6. Paper-trading guard
        if (paperTradingEnabled) {
            log.info("📝 PAPER TRADE: Would place BUY {} shares at {}",
                    orderSize.toPlainString(), buyPrice.toPlainString());
            log.info("📝 PAPER TRADE: Would place SELL {} shares at {}",
                    orderSize.toPlainString(), sellPrice.toPlainString());
        } else {
            log.info("🚀 LIVE: Placing BUY {} @ {} and SELL {} @ {}",
                    orderSize.toPlainString(), buyPrice.toPlainString(),
                    orderSize.toPlainString(), sellPrice.toPlainString());
            try {
                orderManagementService.placeOrder(buyOrder);
                orderManagementService.placeOrder(sellOrder);
            } catch (Exception e) {
                log.error("❌ Failed to place live orders", e);
            }
        }
    }

    // -----------------------------------------------------------------
    // Accessors (for testing)
    // -----------------------------------------------------------------

    public boolean isPaperTradingEnabled() {
        return paperTradingEnabled;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String getMarketId() {
        return marketId;
    }

    public BigDecimal getTargetSpreadCents() {
        return targetSpreadCents;
    }

    public BigDecimal getOrderSize() {
        return orderSize;
    }
}
