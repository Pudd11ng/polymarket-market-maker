package com.polymarket.marketmaker.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.polymarket.marketmaker.model.Order;
import com.polymarket.marketmaker.model.OrderBook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StrategyEngine}.
 *
 * <p>
 * Uses Mockito to mock {@link OrderManagementService} — no Spring context
 * or network calls required.
 * </p>
 */
class StrategyEngineTest {

    private OrderBook orderBook;
    private OrderManagementService mockOms;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook("test-token");
        mockOms = Mockito.mock(OrderManagementService.class);
    }

    // -----------------------------------------------------------------
    // Skip scenarios
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Skips when the order book is completely empty")
    void testSkipsWhenBookEmpty() {
        StrategyEngine engine = buildEngine(true, "0.02", "100");
        engine.evaluateAndQuote();

        verify(mockOms, never()).placeOrder(any());
    }

    @Test
    @DisplayName("Skips when only bids are present (no asks)")
    void testSkipsWhenNoAsks() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));

        StrategyEngine engine = buildEngine(true, "0.02", "100");
        engine.evaluateAndQuote();

        verify(mockOms, never()).placeOrder(any());
    }

    @Test
    @DisplayName("Skips when the spread is too tight")
    void testSkipsWhenSpreadTooTight() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        orderBook.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.51"), new BigDecimal("100"));
        // spread = 0.01, target = 0.02 → too tight

        StrategyEngine engine = buildEngine(true, "0.02", "100");
        engine.evaluateAndQuote();

        verify(mockOms, never()).placeOrder(any());
    }

    // -----------------------------------------------------------------
    // Paper-trading mode
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Paper trading: logs but does NOT call placeOrder")
    void testPaperTradeLogsInsteadOfPlacing() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        orderBook.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.55"), new BigDecimal("100"));
        // spread = 0.05 >= target 0.02 → triggers

        StrategyEngine engine = buildEngine(true, "0.02", "100");
        engine.evaluateAndQuote();

        // Paper trading → OMS must NEVER be called
        verify(mockOms, never()).placeOrder(any());
    }

    // -----------------------------------------------------------------
    // Live trading mode
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Live trading: calls placeOrder exactly twice (BUY + SELL)")
    void testLiveTradeCallsOrderManagement() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        orderBook.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.55"), new BigDecimal("100"));

        when(mockOms.placeOrder(any())).thenReturn("{\"success\":true}");

        StrategyEngine engine = buildEngine(false, "0.02", "100");
        engine.evaluateAndQuote();

        // Live mode → exactly 2 placeOrder calls (BUY + SELL)
        verify(mockOms, times(2)).placeOrder(any(Order.class));
    }

    // -----------------------------------------------------------------
    // Quote pricing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Quote prices: BUY at bestBid + 0.01, SELL at bestAsk − 0.01")
    void testQuotePricesAreCorrect() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        orderBook.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.55"), new BigDecimal("100"));

        when(mockOms.placeOrder(any())).thenReturn("{\"success\":true}");

        StrategyEngine engine = buildEngine(false, "0.02", "50");
        engine.evaluateAndQuote();

        // Capture the two orders placed
        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(mockOms, times(2)).placeOrder(captor.capture());

        Order buyOrder = captor.getAllValues().get(0);
        Order sellOrder = captor.getAllValues().get(1);

        assertEquals(Order.Side.BUY, buyOrder.getSide());
        assertEquals(new BigDecimal("0.51"), buyOrder.getPrice()); // bestBid(0.50) + 0.01
        assertEquals(new BigDecimal("50"), buyOrder.getSize());

        assertEquals(Order.Side.SELL, sellOrder.getSide());
        assertEquals(new BigDecimal("0.54"), sellOrder.getPrice()); // bestAsk(0.55) − 0.01
        assertEquals(new BigDecimal("50"), sellOrder.getSize());
    }

    // -----------------------------------------------------------------
    // Edge: spread exactly at target
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Spread exactly equals target → should still quote")
    void testSpreadExactlyAtTarget() {
        orderBook.updateLevel(OrderBook.Side.BID, new BigDecimal("0.50"), new BigDecimal("100"));
        orderBook.updateLevel(OrderBook.Side.ASK, new BigDecimal("0.52"), new BigDecimal("100"));
        // spread = 0.02 == target 0.02 → should trigger

        when(mockOms.placeOrder(any())).thenReturn("{\"success\":true}");

        StrategyEngine engine = buildEngine(false, "0.02", "100");
        engine.evaluateAndQuote();

        verify(mockOms, times(2)).placeOrder(any(Order.class));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private StrategyEngine buildEngine(boolean paperTrading,
            String targetSpread, String orderSize) {
        return new StrategyEngine(
                orderBook,
                mockOms,
                new BigDecimal(targetSpread),
                new BigDecimal(orderSize),
                paperTrading);
    }
}
