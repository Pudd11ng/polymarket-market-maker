package com.polymarket.marketmaker.model.risk;

/**
 * Risk classification for individual positions.
 *
 * <p>
 * Thresholds (unrealized P&amp;L percentage):
 * <ul>
 * <li>{@code LOW} — loss &lt; 2%</li>
 * <li>{@code MEDIUM} — loss between 2% and 5%</li>
 * <li>{@code HIGH} — loss between 5% and 10%</li>
 * <li>{@code CRITICAL} — loss &ge; 10%</li>
 * </ul>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
