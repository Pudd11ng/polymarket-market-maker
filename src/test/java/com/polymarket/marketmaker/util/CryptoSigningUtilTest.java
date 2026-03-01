package com.polymarket.marketmaker.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;

import com.polymarket.marketmaker.model.Order;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CryptoSigningUtil}.
 *
 * <p>
 * Uses a deterministic private key so signatures are reproducible.
 * </p>
 */
class CryptoSigningUtilTest {

    /** Deterministic test private key (DO NOT use in production). */
    private static final String TEST_PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static final String TEST_TOKEN_ID = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    private CryptoSigningUtil signingUtil;
    private Credentials credentials;

    @BeforeEach
    void setUp() {
        signingUtil = new CryptoSigningUtil();
        credentials = Credentials.create(TEST_PRIVATE_KEY);
    }

    // -----------------------------------------------------------------
    // Domain separator
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Domain separator is constant across calls")
    void testDomainSeparatorIsConstant() {
        byte[] first = signingUtil.getDomainSeparator();
        byte[] second = signingUtil.getDomainSeparator();

        assertArrayEquals(first, second, "Domain separator must be deterministic");
        assertEquals(32, first.length, "Domain separator must be 32 bytes (keccak256)");
    }

    // -----------------------------------------------------------------
    // Order signing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("signOrder produces a 130-hex-char (65 byte) signature")
    void testSignatureLength() {
        Order order = buildTestOrder();
        String signature = signingUtil.signOrder(order, credentials);

        assertNotNull(signature);
        assertEquals(130, signature.length(),
                "Signature must be 130 hex chars (r=64 + s=64 + v=2)");
    }

    @Test
    @DisplayName("signOrder is deterministic: same order + key = same signature")
    void testSignOrderProducesDeterministicSignature() {
        Order order1 = buildTestOrder();
        Order order2 = buildTestOrder();

        String sig1 = signingUtil.signOrder(order1, credentials);
        String sig2 = signingUtil.signOrder(order2, credentials);

        assertEquals(sig1, sig2,
                "Identical orders signed with the same key must produce identical signatures");
    }

    @Test
    @DisplayName("Different orders produce different signatures")
    void testDifferentOrdersDifferentSignatures() {
        Order order1 = buildTestOrder();
        Order order2 = buildTestOrder();
        order2.setSalt(BigInteger.valueOf(99999)); // change salt → different hash

        String sig1 = signingUtil.signOrder(order1, credentials);
        String sig2 = signingUtil.signOrder(order2, credentials);

        assertNotEquals(sig1, sig2,
                "Different orders must produce different signatures");
    }

    @Test
    @DisplayName("Signature v value is 27 or 28")
    void testSignatureVValue() {
        Order order = buildTestOrder();
        String signature = signingUtil.signOrder(order, credentials);

        // v is the last byte (last 2 hex chars)
        int v = Integer.parseInt(signature.substring(128), 16);
        assertTrue(v == 27 || v == 28, "v must be 27 or 28, got: " + v);
    }

    // -----------------------------------------------------------------
    // HMAC
    // -----------------------------------------------------------------

    @Test
    @DisplayName("HMAC signature is non-null and Base64-encoded")
    void testHmacSignatureGeneration() {
        // A known Base64-encoded secret
        String secret = java.util.Base64.getEncoder().encodeToString("test-secret".getBytes());
        String timestamp = "1700000000";
        String method = "POST";
        String path = "/order";
        String body = "{\"test\":true}";

        String hmac = signingUtil.generateHmacSignature(secret, timestamp, method, path, body);

        assertNotNull(hmac);
        assertFalse(hmac.isEmpty());
        // Verify it decodes as valid Base64
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(hmac),
                "HMAC signature must be valid Base64");
    }

    @Test
    @DisplayName("HMAC is deterministic for same inputs")
    void testHmacDeterministic() {
        String secret = java.util.Base64.getEncoder().encodeToString("deterministic".getBytes());
        String hmac1 = signingUtil.generateHmacSignature(secret, "12345", "GET", "/test", "");
        String hmac2 = signingUtil.generateHmacSignature(secret, "12345", "GET", "/test", "");

        assertEquals(hmac1, hmac2);
    }

    // -----------------------------------------------------------------
    // Digest
    // -----------------------------------------------------------------

    @Test
    @DisplayName("computeDigest produces a 32-byte hash")
    void testComputeDigestLength() {
        Order order = buildTestOrder();
        byte[] digest = signingUtil.computeDigest(order);

        assertEquals(32, digest.length, "Digest must be 32 bytes (keccak256)");
    }

    @Test
    @DisplayName("computeDigest is deterministic")
    void testComputeDigestDeterministic() {
        Order order1 = buildTestOrder();
        Order order2 = buildTestOrder();

        byte[] d1 = signingUtil.computeDigest(order1);
        byte[] d2 = signingUtil.computeDigest(order2);

        assertArrayEquals(d1, d2, "Same order must produce same digest");
    }

    // -----------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------

    private Order buildTestOrder() {
        return Order.builder()
                .tokenId(TEST_TOKEN_ID)
                .side(Order.Side.BUY)
                .price(new BigDecimal("0.55"))
                .size(new BigDecimal("100"))
                .salt(BigInteger.valueOf(12345))
                .maker(credentials.getAddress())
                .signer(credentials.getAddress())
                .taker("0x0000000000000000000000000000000000000000")
                .makerAmount(BigInteger.valueOf(55_000_000)) // 0.55 × 100 × 10^6
                .takerAmount(BigInteger.valueOf(100_000_000)) // 100 × 10^6
                .expiration(0)
                .nonce(BigInteger.ZERO)
                .feeRateBps(0)
                .signatureType(0)
                .build();
    }
}
