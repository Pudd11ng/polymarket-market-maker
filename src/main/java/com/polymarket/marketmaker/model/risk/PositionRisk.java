package com.polymarket.marketmaker.model.risk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mutable risk snapshot for a single open position.
 *
 * <p>
 * Fields that change every tick ({@code currentPrice},
 * {@code unrealizedPnl}, {@code riskLevel}, {@code timeHeldSeconds})
 * are updated in-place by
 * {@link com.polymarket.marketmaker.service.RiskEngine#updatePositions}
 * to minimize GC pressure at 20 Hz.
 * </p>
 */
public class PositionRisk {

    private final String positionId;
    private final BigDecimal size;
    private final BigDecimal entryPrice;
    private final Instant entryTime;

    // --- Mutable, updated every tick ---
    private volatile BigDecimal currentPrice;
    private volatile BigDecimal unrealizedPnl;
    private volatile RiskLevel riskLevel;
    private volatile long timeHeldSeconds;

    public PositionRisk(String positionId, BigDecimal size, BigDecimal entryPrice) {
        this.positionId = positionId;
        this.size = size;
        this.entryPrice = entryPrice;
        this.entryTime = Instant.now();
        this.currentPrice = entryPrice;
        this.unrealizedPnl = BigDecimal.ZERO;
        this.riskLevel = RiskLevel.LOW;
        this.timeHeldSeconds = 0;
    }

    // --- Getters ---

    public String getPositionId() {
        return positionId;
    }

    public BigDecimal getSize() {
        return size;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public BigDecimal getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public long getTimeHeldSeconds() {
        return timeHeldSeconds;
    }

    // --- Setters (called by RiskEngine on each tick) ---

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public void setUnrealizedPnl(BigDecimal unrealizedPnl) {
        this.unrealizedPnl = unrealizedPnl;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public void setTimeHeldSeconds(long timeHeldSeconds) {
        this.timeHeldSeconds = timeHeldSeconds;
    }

    @Override
    public String toString() {
        return String.format("Position[%s size=%s entry=%s current=%s pnl=%s risk=%s held=%ds]",
                positionId, size, entryPrice, currentPrice, unrealizedPnl, riskLevel, timeHeldSeconds);
    }
}
