package com.polymarket.marketmaker.model.risk;

import java.math.BigDecimal;

/**
 * Immutable snapshot of the risk engine state for logging and dashboarding.
 *
 * @param activePositions    number of open positions
 * @param totalExposure      sum of all position notionals
 * @param totalUnrealizedPnl aggregate unrealized P&amp;L
 * @param dailyPnl           realized P&amp;L for the current day
 * @param currentBalance     current account balance
 * @param peakBalance        highest balance seen (for drawdown calc)
 * @param currentDrawdownPct current drawdown from peak as a decimal (e.g., 0.05
 *                           = 5%)
 * @param worstRiskLevel     highest risk level across all positions
 */
public record RiskSummary(
        int activePositions,
        BigDecimal totalExposure,
        BigDecimal totalUnrealizedPnl,
        BigDecimal dailyPnl,
        BigDecimal currentBalance,
        BigDecimal peakBalance,
        double currentDrawdownPct,
        RiskLevel worstRiskLevel) {
}
