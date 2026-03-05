package com.polymarket.marketmaker.model.risk;

import java.math.BigDecimal;

/**
 * Immutable risk limit parameters. Defaults are conservative for
 * paper-trading in a 15-minute Bitcoin market.
 *
 * @param maxPositionSize  max notional for a single position ($1.00)
 * @param maxTotalExposure max aggregate notional across all positions ($10.00)
 * @param maxPositions     max number of concurrent positions (5)
 * @param maxDrawdownPct   max drawdown from peak balance before halt (15%)
 * @param maxDailyLoss     max realized daily loss before halt ($5.00)
 */
public record RiskLimits(
        BigDecimal maxPositionSize,
        BigDecimal maxTotalExposure,
        int maxPositions,
        double maxDrawdownPct,
        BigDecimal maxDailyLoss) {
    /** Conservative defaults for paper trading. */
    public static final RiskLimits DEFAULT = new RiskLimits(
            new BigDecimal("1.00"),
            new BigDecimal("10.00"),
            5,
            0.15,
            new BigDecimal("5.00"));
}
