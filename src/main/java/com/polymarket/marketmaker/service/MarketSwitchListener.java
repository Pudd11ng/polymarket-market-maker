package com.polymarket.marketmaker.service;

/**
 * Callback interface for components that need to react when the
 * active market token changes (e.g., a new 15-minute Bitcoin market).
 *
 * <p>
 * Implementors: {@link MarketDataService} (switches WS subscription)
 * and {@link StrategyEngine} (pauses, updates tokenId, resumes).
 * </p>
 */
public interface MarketSwitchListener {

    /**
     * Called by {@link MarketDiscoveryService} when a new active token
     * is discovered via the Gamma API.
     *
     * @param newTokenId the CLOB token ID of the new active market
     */
    void onMarketSwitch(String newTokenId);
}
