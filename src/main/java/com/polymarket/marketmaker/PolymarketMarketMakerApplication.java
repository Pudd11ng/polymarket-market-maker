package com.polymarket.marketmaker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Polymarket Market-Maker application.
 */
@SpringBootApplication
@EnableScheduling
public class PolymarketMarketMakerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolymarketMarketMakerApplication.class, args);
    }
}
