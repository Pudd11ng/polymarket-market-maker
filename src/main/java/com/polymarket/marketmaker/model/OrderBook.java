package com.polymarket.marketmaker.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thread-safe, in-memory order book for a single Polymarket token.
 *
 * <p>
 * Uses {@link ConcurrentSkipListMap} for both sides:
 * </p>
 * <ul>
 * <li><b>Bids</b> — descending order (highest price = first entry)</li>
 * <li><b>Asks</b> — ascending order (lowest price = first entry)</li>
 * </ul>
 *
 * <p>
 * {@code ConcurrentSkipListMap} is ideal here because:
 * </p>
 * <ol>
 * <li>Lock-free reads — {@code firstEntry()} / {@code get()} never block,
 * which is critical on the hot path where the strategy thread reads
 * best-bid / best-ask every tick.</li>
 * <li>O(log n) insert / delete / lookup — matches the theoretical optimum
 * for a sorted, concurrent data structure.</li>
 * <li>Sorted iteration — we can stream the top-N levels without a copy.</li>
 * </ol>
 */
public class OrderBook {

    /** Polymarket condition / token identifier. */
    private final String tokenId;

    /**
     * Bid side — highest price first (descending).
     * Key = price, Value = aggregate size at that price.
     */
    private final ConcurrentSkipListMap<BigDecimal, BigDecimal> bids;

    /**
     * Ask side — lowest price first (ascending / natural order).
     * Key = price, Value = aggregate size at that price.
     */
    private final ConcurrentSkipListMap<BigDecimal, BigDecimal> asks;

    /** Epoch millis of the most recent update applied to either side. */
    private volatile long lastUpdatedMs;

    // -----------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------

    public OrderBook(String tokenId) {
        this.tokenId = tokenId;
        this.bids = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.asks = new ConcurrentSkipListMap<>(); // natural ascending order
    }

    // -----------------------------------------------------------------
    // Core mutations
    // -----------------------------------------------------------------

    /**
     * Insert or update a price level on the given side.
     * If {@code size} is zero or negative the level is <em>removed</em>.
     *
     * <p>
     * This method is safe to call from any thread.
     * </p>
     *
     * @param side  BID or ASK
     * @param price the price level
     * @param size  aggregate size; ≤ 0 means "remove this level"
     */
    public void updateLevel(Side side, BigDecimal price, BigDecimal size) {
        ConcurrentSkipListMap<BigDecimal, BigDecimal> book = (side == Side.BID) ? bids : asks;

        if (size.compareTo(BigDecimal.ZERO) <= 0) {
            book.remove(price);
        } else {
            book.put(price, size);
        }

        this.lastUpdatedMs = System.currentTimeMillis();
    }

    /**
     * Clears both sides of the book. Useful on reconnect / full-snapshot reset.
     */
    public void clear() {
        bids.clear();
        asks.clear();
        this.lastUpdatedMs = System.currentTimeMillis();
    }

    // -----------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------

    /**
     * @return the highest bid (price → size), or empty if the bid book is empty.
     */
    public Optional<Map.Entry<BigDecimal, BigDecimal>> getBestBid() {
        return Optional.ofNullable(bids.firstEntry());
    }

    /**
     * @return the lowest ask (price → size), or empty if the ask book is empty.
     */
    public Optional<Map.Entry<BigDecimal, BigDecimal>> getBestAsk() {
        return Optional.ofNullable(asks.firstEntry());
    }

    /**
     * @return best-ask minus best-bid, or empty if either side is empty.
     */
    public Optional<BigDecimal> getSpread() {
        return getBestAsk().flatMap(ask -> getBestBid().map(bid -> ask.getKey().subtract(bid.getKey())));
    }

    /**
     * @return an unmodifiable snapshot of the bid book (descending by price).
     */
    public NavigableMap<BigDecimal, BigDecimal> getBidsSnapshot() {
        return Collections.unmodifiableNavigableMap(bids);
    }

    /**
     * @return an unmodifiable snapshot of the ask book (ascending by price).
     */
    public NavigableMap<BigDecimal, BigDecimal> getAsksSnapshot() {
        return Collections.unmodifiableNavigableMap(asks);
    }

    public int getBidDepth() {
        return bids.size();
    }

    public int getAskDepth() {
        return asks.size();
    }

    public String getTokenId() {
        return tokenId;
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    // -----------------------------------------------------------------
    // Enums & DTOs
    // -----------------------------------------------------------------

    /** Side of the book. */
    public enum Side {
        BID, ASK
    }

    /**
     * Convenience DTO for serialization / REST responses.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceLevel {
        private BigDecimal price;
        private BigDecimal size;
    }

    @Override
    public String toString() {
        return "OrderBook{" +
                "tokenId='" + tokenId + '\'' +
                ", bestBid=" + getBestBid().map(e -> e.getKey().toPlainString()).orElse("---") +
                ", bestAsk=" + getBestAsk().map(e -> e.getKey().toPlainString()).orElse("---") +
                ", spread=" + getSpread().map(BigDecimal::toPlainString).orElse("---") +
                ", bidDepth=" + getBidDepth() +
                ", askDepth=" + getAskDepth() +
                '}';
    }
}
