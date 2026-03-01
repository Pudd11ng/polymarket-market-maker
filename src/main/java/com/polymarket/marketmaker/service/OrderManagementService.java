package com.polymarket.marketmaker.service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.Credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.marketmaker.model.Order;
import com.polymarket.marketmaker.util.CryptoSigningUtil;

/**
 * Handles order lifecycle — placement and cancellation — against the
 * Polymarket CLOB REST API.
 *
 * <h3>Authentication model</h3>
 * <ul>
 * <li><b>L1 (EIP-712)</b>: Each order payload is signed with the wallet's
 * private key via {@link CryptoSigningUtil#signOrder}.</li>
 * <li><b>L2 (HMAC-SHA256)</b>: Every HTTP request carries 6 {@code POLY_*}
 * headers authenticated with the API secret.</li>
 * </ul>
 *
 * <p>
 * <b>Scope</b>: This service places and cancels individual orders.
 * It does NOT contain any trading strategy logic (Sprint 4+).
 * </p>
 */
@Service
public class OrderManagementService {

    private static final Logger log = LoggerFactory.getLogger(OrderManagementService.class);

    private static final String ORDER_PATH = "/order";

    private final CryptoSigningUtil cryptoSigningUtil;
    private final Credentials credentials;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // L2 API credentials
    @Value("${polymarket.api.key:}")
    private String apiKey;

    @Value("${polymarket.api.secret:}")
    private String apiSecret;

    @Value("${polymarket.api.passphrase:}")
    private String apiPassphrase;

    @Value("${polymarket.api.fee-rate-bps:0}")
    private int defaultFeeRateBps;

    public OrderManagementService(
            CryptoSigningUtil cryptoSigningUtil,
            Credentials credentials,
            @Value("${polymarket.api.url}") String apiBaseUrl) {
        this.cryptoSigningUtil = cryptoSigningUtil;
        this.credentials = credentials;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OrderManagementService initialized — API base URL: {}", apiBaseUrl);
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    /**
     * Places an order on the Polymarket CLOB.
     *
     * <ol>
     * <li>Populates on-chain fields (salt, maker, signer, amounts)</li>
     * <li>Signs the order with EIP-712</li>
     * <li>Sends POST /order with L2 HMAC headers</li>
     * </ol>
     *
     * @param order the order to place (price, size, side, tokenId must be set)
     * @return the API response body as a string
     */
    public String placeOrder(Order order) {
        // 1. Populate on-chain fields
        populateOnChainFields(order);

        // 2. Sign the order (EIP-712)
        String signature = cryptoSigningUtil.signOrder(order, credentials);

        // 3. Build the request body
        Map<String, Object> body = buildOrderRequestBody(order, signature);

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());

            log.info("📤 Placing order — tokenId={}, side={}, price={}, size={}",
                    order.getTokenId(), order.getSide(), order.getPrice(), order.getSize());

            String response = restClient.post()
                    .uri(ORDER_PATH)
                    .headers(headers -> addL2Headers(headers, "POST", ORDER_PATH, jsonBody, timestamp))
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);

            log.info("✅ Order placed — response: {}", response);
            order.setStatus(Order.OrderStatus.OPEN);
            return response;

        } catch (Exception e) {
            log.error("❌ Failed to place order", e);
            order.setStatus(Order.OrderStatus.REJECTED);
            throw new RuntimeException("Order placement failed", e);
        }
    }

    /**
     * Cancels an existing order on the Polymarket CLOB.
     *
     * @param orderId the order ID to cancel
     * @return the API response body as a string
     */
    public String cancelOrder(String orderId) {
        String path = ORDER_PATH + "/" + orderId;
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        log.info("🗑️ Cancelling order — orderId={}", orderId);

        try {
            String response = restClient.delete()
                    .uri(path)
                    .headers(headers -> addL2Headers(headers, "DELETE", path, "", timestamp))
                    .retrieve()
                    .body(String.class);

            log.info("✅ Order cancelled — response: {}", response);
            return response;

        } catch (Exception e) {
            log.error("❌ Failed to cancel order — orderId={}", orderId, e);
            throw new RuntimeException("Order cancellation failed", e);
        }
    }

    // -----------------------------------------------------------------
    // Internal — On-chain field population
    // -----------------------------------------------------------------

    /**
     * Fills in the EIP-712 on-chain fields that the strategy layer doesn't
     * need to know about (salt, maker, signer, amounts, fee).
     */
    private void populateOnChainFields(Order order) {
        String walletAddress = credentials.getAddress();

        if (order.getSalt() == null) {
            order.setSalt(new BigInteger(128, new java.security.SecureRandom()));
        }
        if (order.getMaker() == null) {
            order.setMaker(walletAddress);
        }
        if (order.getSigner() == null) {
            order.setSigner(walletAddress);
        }
        if (order.getFeeRateBps() == 0) {
            order.setFeeRateBps(defaultFeeRateBps);
        }
        if (order.getOrderId() == null) {
            order.setOrderId(UUID.randomUUID().toString());
        }
        if (order.getCreatedAtMs() == 0) {
            order.setCreatedAtMs(System.currentTimeMillis());
        }

        // Convert human-readable price/size → raw token amounts
        // Polymarket uses USDC (6 decimals) for amounts
        if (order.getMakerAmount() == null && order.getPrice() != null && order.getSize() != null) {
            // For a BUY: makerAmount = price × size (what you pay in USDC)
            // takerAmount = size (what you receive in outcome tokens)
            // For a SELL: makerAmount = size (outcome tokens you sell)
            // takerAmount = price × size (USDC you receive)
            var usdc6 = order.getPrice().multiply(order.getSize())
                    .movePointRight(6).toBigInteger();
            var tokens6 = order.getSize().movePointRight(6).toBigInteger();

            if (order.getSide() == Order.Side.BUY) {
                order.setMakerAmount(usdc6);
                order.setTakerAmount(tokens6);
            } else {
                order.setMakerAmount(tokens6);
                order.setTakerAmount(usdc6);
            }
        }
    }

    // -----------------------------------------------------------------
    // Internal — Request body
    // -----------------------------------------------------------------

    private Map<String, Object> buildOrderRequestBody(Order order, String signature) {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("salt", order.getSalt().toString());
        orderMap.put("maker", order.getMaker());
        orderMap.put("signer", order.getSigner());
        orderMap.put("taker", order.getTaker());
        orderMap.put("tokenId", order.getTokenId());
        orderMap.put("makerAmount", order.getMakerAmount().toString());
        orderMap.put("takerAmount", order.getTakerAmount().toString());
        orderMap.put("expiration", String.valueOf(order.getExpiration()));
        orderMap.put("nonce", order.getNonce().toString());
        orderMap.put("feeRateBps", String.valueOf(order.getFeeRateBps()));
        orderMap.put("side", String.valueOf(order.getSide().toUint8()));
        orderMap.put("signatureType", String.valueOf(order.getSignatureType()));
        body.put("order", orderMap);
        body.put("signature", "0x" + signature);
        body.put("owner", order.getMaker());
        body.put("orderType", "LMT"); // Limit order
        return body;
    }

    // -----------------------------------------------------------------
    // Internal — L2 HMAC Authentication Headers
    // -----------------------------------------------------------------

    /**
     * Adds the 6 Polymarket L2 authentication headers to the request.
     *
     * <pre>
     *   POLY_ADDRESS     = wallet address
     *   POLY_API_KEY     = API key
     *   POLY_PASSPHRASE  = API passphrase
     *   POLY_TIMESTAMP   = current unix timestamp (seconds)
     *   POLY_NONCE       = "0"
     *   POLY_SIGNATURE   = HMAC-SHA256(timestamp + method + path + body, secret)
     * </pre>
     */
    private void addL2Headers(HttpHeaders headers, String method,
            String path, String body, String timestamp) {
        String hmacSignature = cryptoSigningUtil.generateHmacSignature(
                apiSecret, timestamp, method, path, body);

        headers.set("POLY_ADDRESS", credentials.getAddress());
        headers.set("POLY_API_KEY", apiKey);
        headers.set("POLY_PASSPHRASE", apiPassphrase);
        headers.set("POLY_TIMESTAMP", timestamp);
        headers.set("POLY_NONCE", "0");
        headers.set("POLY_SIGNATURE", hmacSignature);
    }
}
