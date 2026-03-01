package com.polymarket.marketmaker.service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.marketmaker.config.WebSocketConfig;
import com.polymarket.marketmaker.model.OrderBook;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Consumes real-time market data from the Polymarket CLOB WebSocket feed
 * and maintains the local {@link OrderBook} state.
 *
 * <h3>Reconnection strategy</h3>
 * <p>
 * Uses <b>exponential backoff</b> so we don't hammer the server after a drop:
 * 
 * <pre>
 *   delay = min(initialBackoff × multiplier^attempt, maxBackoff)
 * </pre>
 * 
 * The attempt counter resets to zero on every successful {@code onOpen}.
 *
 * <h3>Scope</h3>
 * <p>
 * This service is <em>read-only</em> — it never places, cancels, or
 * modifies orders. Order management belongs to a separate service
 * (Sprint 3+).
 * </p>
 */
@Service
public class MarketDataService implements MarketSwitchListener {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private volatile String marketId;

    private final WebSocketConfig wsConfig;
    private final OrderBook orderBook;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile PolymarketWebSocketClient wsClient;
    private volatile boolean shutdownRequested = false;

    public MarketDataService(WebSocketConfig wsConfig, OrderBook orderBook,
            @Value("${polymarket.market-id}") String marketId) {
        this.wsConfig = wsConfig;
        this.orderBook = orderBook;
        this.marketId = marketId;
        this.objectMapper = new ObjectMapper();
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    /**
     * Opens the WebSocket connection on application startup.
     */
    @PostConstruct
    public void connect() {
        log.info("▶ MarketDataService starting — connecting to {}", wsConfig.getPolymarketWsUrl());
        createAndConnectClient();
    }

    /**
     * Gracefully closes the WebSocket and shuts down the reconnect scheduler.
     */
    @PreDestroy
    public void disconnect() {
        log.info("◼ MarketDataService shutting down");
        shutdownRequested = true;
        reconnectScheduler.shutdownNow();
        if (wsClient != null) {
            wsClient.close();
        }
    }

    // -----------------------------------------------------------------
    // MarketSwitchListener — safe transition
    // -----------------------------------------------------------------

    /**
     * Called by {@link MarketDiscoveryService} when the active market changes.
     * Clears the order book and switches the WebSocket subscription.
     */
    @Override
    public void onMarketSwitch(String newTokenId) {
        log.info("🔄 MarketDataService switching market → {}", newTokenId);
        switchMarket(newTokenId);
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    /**
     * Returns the current in-memory order book (live reference).
     */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * Switches the WebSocket subscription to a new market token.
     * <ol>
     * <li>Clear stale order book data</li>
     * <li>Update internal market ID</li>
     * <li>Subscribe to new token on the WebSocket</li>
     * </ol>
     *
     * @param newTokenId the new CLOB token ID
     */
    public void switchMarket(String newTokenId) {
        log.info("🔄 Switching subscription: {} → {}", this.marketId, newTokenId);

        // 1. Clear stale data
        orderBook.clear();

        // 2. Update internal state
        this.marketId = newTokenId;

        // 3. Subscribe to new market
        subscribeToMarket(newTokenId);
    }

    /**
     * Sends a subscription frame for the given asset / market ID.
     * Must be called after the connection is open.
     *
     * @param assetId the Polymarket asset identifier
     */
    public void subscribeToMarket(String assetId) {
        if (wsClient != null && wsClient.isOpen()) {
            String subscriptionMsg = String.format(
                    "{\"type\":\"market\",\"assets_id\":\"%s\"}", assetId);
            wsClient.send(subscriptionMsg);
            log.info("📡 Subscribed to market asset_id={}", assetId);
        } else {
            log.warn("Cannot subscribe — WebSocket is not open");
        }
    }

    // -----------------------------------------------------------------
    // Internal — connection management
    // -----------------------------------------------------------------

    private void createAndConnectClient() {
        try {
            URI uri = new URI(wsConfig.getPolymarketWsUrl());
            wsClient = new PolymarketWebSocketClient(uri);
            wsClient.connect(); // non-blocking
        } catch (Exception e) {
            log.error("Failed to create WebSocket client", e);
            scheduleReconnect();
        }
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     *
     * <p>
     * Delay formula:
     * 
     * <pre>
     * delay = min(initialBackoff × multiplier^attempt, maxBackoff)
     * </pre>
     */
    private void scheduleReconnect() {
        if (shutdownRequested) {
            log.debug("Shutdown requested — skipping reconnect");
            return;
        }

        int attempt = reconnectAttempts.getAndIncrement();
        long delay = Math.min(
                (long) (wsConfig.getInitialBackoffMs() * Math.pow(wsConfig.getBackoffMultiplier(), attempt)),
                wsConfig.getMaxBackoffMs());

        log.info("⏳ Scheduling reconnect attempt #{} in {} ms", attempt + 1, delay);

        reconnectScheduler.schedule(() -> {
            log.info("🔄 Reconnect attempt #{}", attempt + 1);
            createAndConnectClient();
        }, delay, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------
    // Inner class — WebSocket event handler
    // -----------------------------------------------------------------

    /**
     * Thin wrapper around {@link WebSocketClient} that delegates message
     * parsing to the enclosing {@link MarketDataService}.
     */
    private class PolymarketWebSocketClient extends WebSocketClient {

        PolymarketWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("✅ WebSocket connected (status={})", handshake.getHttpStatus());
            reconnectAttempts.set(0); // reset backoff on success

            // Auto-subscribe to the default market
            subscribeToMarket(marketId);
        }

        @Override
        public void onMessage(String message) {
            try {
                parseAndUpdateOrderBook(message);
            } catch (Exception e) {
                log.error("Failed to process WS message: {}", message, e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.warn("⚠ WebSocket closed (code={}, reason={}, remote={})", code, reason, remote);
            orderBook.clear();
            scheduleReconnect();
        }

        @Override
        public void onError(Exception ex) {
            log.error("❌ WebSocket error", ex);
            // onClose will follow — reconnection is handled there
        }
    }

    // -----------------------------------------------------------------
    // JSON parsing — Polymarket CLOB format
    // -----------------------------------------------------------------

    /**
     * Parses a Polymarket CLOB WebSocket message and applies updates to
     * the local order book.
     *
     * <p>
     * Expected JSON format (array-of-arrays):
     * 
     * <pre>{@code
     * {
     *   "market": "0x123...",
     *   "bids": [["0.55", "100"], ["0.54", "200"]],
     *   "asks": [["0.56", "150"], ["0.57", "300"]]
     * }
     * }</pre>
     *
     * A size of {@code "0"} signals removal of that price level.
     */
    void parseAndUpdateOrderBook(String rawJson) throws Exception {
        JsonNode root = objectMapper.readTree(rawJson);

        // --- Bids ---
        JsonNode bidsNode = root.get("bids");
        if (bidsNode != null && bidsNode.isArray()) {
            for (JsonNode level : bidsNode) {
                BigDecimal price = new BigDecimal(level.get(0).asText());
                BigDecimal size = new BigDecimal(level.get(1).asText());
                orderBook.updateLevel(OrderBook.Side.BID, price, size);
            }
        }

        // --- Asks ---
        JsonNode asksNode = root.get("asks");
        if (asksNode != null && asksNode.isArray()) {
            for (JsonNode level : asksNode) {
                BigDecimal price = new BigDecimal(level.get(0).asText());
                BigDecimal size = new BigDecimal(level.get(1).asText());
                orderBook.updateLevel(OrderBook.Side.ASK, price, size);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("📊 Book updated — {}", orderBook);
        }
    }
}
