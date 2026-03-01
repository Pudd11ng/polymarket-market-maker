package com.polymarket.marketmaker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Configuration for Web3j — manages the connection to the Polygon
 * JSON-RPC node and loads wallet credentials from environment variables.
 *
 * TODO (Sprint 2): Wire actual RPC endpoint and credential injection.
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
}
