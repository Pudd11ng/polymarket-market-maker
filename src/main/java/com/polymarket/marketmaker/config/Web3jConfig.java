package com.polymarket.marketmaker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import com.polymarket.marketmaker.model.OrderBook;

/**
 * Central configuration — manages the Polygon JSON-RPC connection,
 * wallet credentials, and the shared {@link OrderBook} singleton.
 */
@Configuration
public class Web3jConfig {

    @Value("${polymarket.wallet.private-key}")
    private String walletPrivateKey;

    @Value("${polymarket.polygon.rpc-url:https://polygon-rpc.com}")
    private String polygonRpcUrl;

    /**
     * Provides a Web3j instance connected to the configured Polygon RPC endpoint.
     */
    @Bean
    public Web3j web3j() {
        return Web3j.build(new HttpService(polygonRpcUrl));
    }

    /**
     * Provides wallet credentials derived from the private key.
     */
    @Bean
    public Credentials credentials() {
        return Credentials.create(walletPrivateKey);
    }

    /**
     * Shared in-memory order book — written by {@code MarketDataService},
     * read by {@code StrategyEngine}.
     */
    @Bean
    public OrderBook orderBook() {
        return new OrderBook("default");
    }
}
