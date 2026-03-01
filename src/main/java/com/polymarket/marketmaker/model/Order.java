package com.polymarket.marketmaker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Represents a single order to be placed on the Polymarket CLOB.
 *
 * <p>
 * Contains both the <b>application-level</b> fields (price, size, status)
 * and the <b>on-chain EIP-712</b> fields required by the CTF Exchange contract
 * ({@code salt}, {@code maker}, {@code makerAmount}, etc.).
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    // -----------------------------------------------------------------
    // Application-level fields
    // -----------------------------------------------------------------

    /** Unique client-side order identifier. */
    private String orderId;

    /** Polymarket token identifier for the market leg. */
    private String tokenId;

    /** BUY or SELL. */
    private Side side;

    /** Limit price (human-readable, e.g. 0.55). */
    private BigDecimal price;

    /** Order size — number of contracts (human-readable). */
    private BigDecimal size;

    /** Current status of the order. */
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    /** Epoch millis when the order was created. */
    private long createdAtMs;

    // -----------------------------------------------------------------
    // On-chain EIP-712 fields (CTF Exchange Order struct)
    // -----------------------------------------------------------------

    /** Random entropy to make each order unique. */
    private BigInteger salt;

    /** Address of the order creator / source of funds. */
    private String maker;

    /** Address that signs the order (usually == maker for EOA). */
    private String signer;

    /** Address of the order taker; zero-address = public order. */
    @Builder.Default
    private String taker = "0x0000000000000000000000000000000000000000";

    /** Raw maker amount in token base units (e.g. USDC with 6 decimals). */
    private BigInteger makerAmount;

    /** Raw taker amount in token base units. */
    private BigInteger takerAmount;

    /** Unix timestamp after which the order expires (0 = no expiry). */
    @Builder.Default
    private long expiration = 0;

    /** On-chain nonce; used for batch-cancellation. */
    @Builder.Default
    private BigInteger nonce = BigInteger.ZERO;

    /** Fee rate in basis points charged to the maker on proceeds. */
    @Builder.Default
    private int feeRateBps = 0;

    /**
     * Signature type: 0 = EOA, 1 = POLY_PROXY, 2 = POLY_GNOSIS_SAFE, 3 = POLY_1271.
     */
    @Builder.Default
    private int signatureType = 0;

    // -----------------------------------------------------------------
    // Enums
    // -----------------------------------------------------------------

    public enum Side {
        BUY, SELL;

        /** Returns the uint8 value expected by the on-chain struct. */
        public int toUint8() {
            return this == BUY ? 0 : 1;
        }
    }

    public enum OrderStatus {
        PENDING, OPEN, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED
    }
}
