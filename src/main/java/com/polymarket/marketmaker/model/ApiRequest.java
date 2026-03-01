package com.polymarket.marketmaker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic wrapper for outbound API requests to the Polymarket CLOB.
 *
 * @param <T> the payload type (e.g., Order, cancel-request body, etc.)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiRequest<T> {

    /** HMAC / EIP-712 signature produced by CryptoSigningUtil. */
    private String signature;

    /** API key or wallet address used for authentication. */
    private String apiKey;

    /** Epoch millis — used as a nonce to prevent replay attacks. */
    private long timestamp;

    /** The actual request payload. */
    private T payload;
}
