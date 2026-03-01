package com.polymarket.marketmaker.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OrderBook}.
 *
 * <p>
 * No Spring context is needed — the order book is a plain Java object.
 * </p>
 */
class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook("test-token-001");
    }

    // -----------------------------------------------------------------
    // Bid tests
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Bids: best bid is the highest price")
    void testUpdateAndGetBestBid() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), new BigDecimal("200"));
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.52"), new BigDecimal("150"));

        Optional<Map.Entry<BigDecimal, BigDecimal>> best = book.getBestBid();

        assertTrue(best.isPresent(), "Expected a best bid");
        assertEquals(new BigDecimal("0.55"), best.get().getKey(), "Best bid should be highest price");
        assertEquals(new BigDecimal("200"), best.get().getValue(), "Best bid size should match");
    }

    @Test
    @DisplayName("Bids: updating an existing level replaces the size")
    void testBidLevelUpdate() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("999"));

        assertEquals(1, book.getBidDepth(), "Should have exactly one bid level");
        assertEquals(new BigDecimal("999"), book.getBestBid().get().getValue());
    }

    // -----------------------------------------------------------------
    // Ask tests
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Asks: best ask is the lowest price")
    void testUpdateAndGetBestAsk() {
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.60"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.56"), new BigDecimal("200"));
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.58"), new BigDecimal("150"));

        Optional<Map.Entry<BigDecimal, BigDecimal>> best = book.getBestAsk();

        assertTrue(best.isPresent(), "Expected a best ask");
        assertEquals(new BigDecimal("0.56"), best.get().getKey(), "Best ask should be lowest price");
        assertEquals(new BigDecimal("200"), best.get().getValue(), "Best ask size should match");
    }

    // -----------------------------------------------------------------
    // Remove-on-zero tests
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Removing a level: size=0 removes the level entirely")
    void testRemoveLevelOnZeroSize() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), new BigDecimal("200"));
        assertEquals(2, book.getBidDepth());

        // Remove the best bid by setting size to 0
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), BigDecimal.ZERO);

        assertEquals(1, book.getBidDepth(), "One level should remain");
        assertEquals(new BigDecimal("0.50"), book.getBestBid().get().getKey(),
                "Best bid should fall back to next level");
    }

    @Test
    @DisplayName("Removing a level: negative size also removes")
    void testRemoveLevelOnNegativeSize() {
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.60"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.60"), new BigDecimal("-1"));

        assertEquals(0, book.getAskDepth(), "Negative size should remove the level");
    }

    // -----------------------------------------------------------------
    // Spread
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Spread: bestAsk − bestBid")
    void testSpreadCalculation() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.58"), new BigDecimal("100"));

        Optional<BigDecimal> spread = book.getSpread();

        assertTrue(spread.isPresent());
        assertEquals(new BigDecimal("0.03"), spread.get(), "Spread should be 0.58 - 0.55 = 0.03");
    }

    @Test
    @DisplayName("Spread: empty when one side is missing")
    void testSpreadEmptyWhenOneSideMissing() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), new BigDecimal("100"));

        assertTrue(book.getSpread().isEmpty(), "Spread should be empty with no asks");
    }

    // -----------------------------------------------------------------
    // Clear
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Clear empties both sides")
    void testClear() {
        book.updateLevel(OrderBook.Side.BID, new BigDecimal("0.55"), new BigDecimal("100"));
        book.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.58"), new BigDecimal("100"));

        book.clear();

        assertEquals(0, book.getBidDepth());
        assertEquals(0, book.getAskDepth());
        assertTrue(book.getBestBid().isEmpty());
        assertTrue(book.getBestAsk().isEmpty());
    }

    // -----------------------------------------------------------------
    // Empty-book edge cases
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Empty book: getBestBid / getBestAsk return empty")
    void testEmptyBook() {
        assertTrue(book.getBestBid().isEmpty());
        assertTrue(book.getBestAsk().isEmpty());
        assertTrue(book.getSpread().isEmpty());
        assertEquals(0, book.getBidDepth());
        assertEquals(0, book.getAskDepth());
    }

    // -----------------------------------------------------------------
    // Concurrency
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Concurrent updates: no exceptions and consistent depth")
    void testConcurrentUpdates() throws InterruptedException {
        final int threadCount = 8;
        final int updatesPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        List<Exception> errors = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startGate.await(); // all threads start simultaneously
                    for (int i = 0; i < updatesPerThread; i++) {
                        BigDecimal price = BigDecimal.valueOf(threadId * 1000 + i, 2); // unique prices per thread
                        BigDecimal size = BigDecimal.valueOf(i + 1);
                        OrderBook.Side side = (threadId % 2 == 0) ? OrderBook.Side.BID : OrderBook.Side.ASK;
                        book.updateLevel(side, price, size);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown(); // fire!
        assertTrue(doneGate.await(10, TimeUnit.SECONDS), "All threads should finish within 10s");
        pool.shutdown();

        assertTrue(errors.isEmpty(), "No exceptions expected, got: " + errors);

        // Each thread inserts `updatesPerThread` unique price levels
        // 4 threads write bids + 4 threads write asks
        assertEquals(updatesPerThread * (threadCount / 2), book.getBidDepth(),
                "Bid depth should match expected unique levels");
        assertEquals(updatesPerThread * (threadCount / 2), book.getAskDepth(),
                "Ask depth should match expected unique levels");
    }
}
