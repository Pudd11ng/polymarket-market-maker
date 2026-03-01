package com.polymarket.marketmaker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;

/**
 * Configuration for the Polymarket WebSocket connection and
 * exponential-backoff reconnection strategy.
 *
 * <p>
 * All values can be overridden via environment variables or
 * {@code application.yml} properties.
 * </p>
 */
@Getter
@Configuration
public class WebSocketConfig {

    /** WebSocket endpoint for Polymarket CLOB market data. */
    @Value("${polymarket.ws.url}")
    private String polymarketWsUrl;

    /** Initial delay (ms) before the first reconnection attempt. */
    @Value("${polymarket.ws.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    /** Upper bound (ms) for the backoff delay. */
    @Value("${polymarket.ws.max-backoff-ms:30000}")
    private long maxBackoffMs;

    /** Multiplicative factor applied to the delay after each failed attempt. */
    @Value("${polymarket.ws.backoff-multiplier:2.0}")
    private double backoffMultiplier;
}
