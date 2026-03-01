package com.polymarket.marketmaker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a single order to be placed on the Polymarket CLOB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /** Unique client-side order identifier. */
    private String orderId;

    /** Polymarket token identifier for the market leg. */
    private String tokenId;

    /** BUY or SELL. */
    private Side side;

    /** Limit price. */
    private BigDecimal price;

    /** Order size (number of contracts). */
    private BigDecimal size;

    /** Current status of the order. */
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    /** Epoch millis when the order was created. */
    private long createdAtMs;

    public enum Side {
        BUY, SELL
    }

    public enum OrderStatus {
        PENDING, OPEN, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED
    }
}
