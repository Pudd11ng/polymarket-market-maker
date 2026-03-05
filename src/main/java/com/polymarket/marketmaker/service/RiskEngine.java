package com.polymarket.marketmaker.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.polymarket.marketmaker.model.risk.PositionRisk;
import com.polymarket.marketmaker.model.risk.RiskLevel;
import com.polymarket.marketmaker.model.risk.RiskLimits;
import com.polymarket.marketmaker.model.risk.RiskSummary;
import com.polymarket.marketmaker.model.risk.ValidationResult;

/**
 * Thread-safe, lock-free risk management engine designed for 20 Hz operation.
 *
 * <p>
 * Key design decisions for high-frequency use:
 * </p>
 * <ul>
 * <li>{@link ConcurrentHashMap} for positions — O(1) lock-free reads</li>
 * <li>{@link AtomicReference} for balance/PnL — lock-free CAS updates</li>
 * <li>Pre-allocated {@link ValidationResult#PASS} — zero allocation on hot
 * path</li>
 * <li>In-place mutation of {@link PositionRisk} fields — avoids object
 * churn</li>
 * </ul>
 */
@Service
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    /** Precision context for financial calculations — 16 significant digits. */
    private static final MathContext MC = MathContext.DECIMAL64;

    /** Pre-computed constants to avoid repeated allocation. */
    private static final BigDecimal MAX_POSITION_CAP = new BigDecimal("1.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal NEG_TWO_PCT = new BigDecimal("-0.02");
    private static final BigDecimal NEG_FIVE_PCT = new BigDecimal("-0.05");
    private static final BigDecimal NEG_TEN_PCT = new BigDecimal("-0.10");

    // -----------------------------------------------------------------
    // Thread-safe state
    // -----------------------------------------------------------------

    private final RiskLimits limits;
    private final ConcurrentHashMap<String, PositionRisk> activePositions = new ConcurrentHashMap<>();
    private final AtomicReference<BigDecimal> dailyPnl = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> currentBalance;
    private final AtomicReference<BigDecimal> peakBalance;

    // -----------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------

    public RiskEngine() {
        this(RiskLimits.DEFAULT, new BigDecimal("100.00"));
    }

    public RiskEngine(RiskLimits limits, BigDecimal initialBalance) {
        this.limits = limits;
        this.currentBalance = new AtomicReference<>(initialBalance);
        this.peakBalance = new AtomicReference<>(initialBalance);
        log.info("🛡️ RiskEngine initialized — limits={}, balance={}", limits, initialBalance);
    }

    // -----------------------------------------------------------------
    // Pre-trade validation (called at 20 Hz)
    // -----------------------------------------------------------------

    /**
     * Validates whether a proposed trade passes all risk checks.
     * Designed to be allocation-free on the happy path.
     *
     * @param size         proposed position size (notional)
     * @param currentPrice current market price
     * @return {@link ValidationResult#PASS} or a failure with reason
     */
    public ValidationResult validateNewPosition(BigDecimal size, BigDecimal currentPrice) {
        // 1. Single position size cap
        if (size.compareTo(limits.maxPositionSize()) > 0) {
            return ValidationResult.fail(String.format(
                    "Position size $%s exceeds max $%s", size, limits.maxPositionSize()));
        }

        // 2. Max concurrent positions
        if (activePositions.size() >= limits.maxPositions()) {
            return ValidationResult.fail(String.format(
                    "Max positions reached (%d/%d)", activePositions.size(), limits.maxPositions()));
        }

        // 3. Daily loss limit
        BigDecimal currentDailyPnl = dailyPnl.get();
        if (currentDailyPnl.compareTo(limits.maxDailyLoss().negate()) <= 0) {
            return ValidationResult.fail(String.format(
                    "Daily loss limit hit: $%s (max -$%s)", currentDailyPnl, limits.maxDailyLoss()));
        }

        // 4. Drawdown limit
        double drawdown = calculateDrawdown();
        if (drawdown > limits.maxDrawdownPct()) {
            return ValidationResult.fail(String.format(
                    "Drawdown %.2f%% exceeds max %.2f%%", drawdown * 100, limits.maxDrawdownPct() * 100));
        }

        // 5. Total exposure check
        BigDecimal totalExposure = calculateTotalExposure().add(size, MC);
        if (totalExposure.compareTo(limits.maxTotalExposure()) > 0) {
            return ValidationResult.fail(String.format(
                    "Total exposure $%s would exceed max $%s", totalExposure, limits.maxTotalExposure()));
        }

        return ValidationResult.PASS;
    }

    // -----------------------------------------------------------------
    // Position sizing
    // -----------------------------------------------------------------

    /**
     * Calculates a position size based on signal strength and available capital.
     *
     * <p>
     * Formula: {@code balance × baseRisk × confidence × score}, strictly
     * capped at {@code $1.00} per the institutional reference.
     * </p>
     *
     * @param signalConfidence model confidence (0.0 – 1.0)
     * @param signalScore      signal strength score (0.0 – 1.0)
     * @return recommended position size, never exceeding $1.00
     */
    public BigDecimal calculatePositionSize(double signalConfidence, double signalScore) {
        BigDecimal balance = currentBalance.get();
        double baseRiskPct = 0.02; // 2% of capital per trade

        BigDecimal rawSize = balance
                .multiply(BigDecimal.valueOf(baseRiskPct), MC)
                .multiply(BigDecimal.valueOf(signalConfidence), MC)
                .multiply(BigDecimal.valueOf(signalScore), MC)
                .setScale(2, RoundingMode.DOWN);

        // CRITICAL: hard cap at $1.00
        BigDecimal cappedSize = rawSize.min(MAX_POSITION_CAP);

        log.debug("📐 Position size: raw={} capped={} (confidence={}, score={})",
                rawSize, cappedSize, signalConfidence, signalScore);

        return cappedSize;
    }

    // -----------------------------------------------------------------
    // Position lifecycle
    // -----------------------------------------------------------------

    /**
     * Registers a new position. Called after a fill confirmation.
     *
     * @param positionId unique identifier for the position
     * @param size       notional size
     * @param entryPrice fill price
     */
    public void addPosition(String positionId, BigDecimal size, BigDecimal entryPrice) {
        PositionRisk position = new PositionRisk(positionId, size, entryPrice);
        activePositions.put(positionId, position);
        log.info("📥 Position opened: {} size={} entry={}", positionId, size, entryPrice);
    }

    /**
     * Closes a position and realizes P&amp;L.
     *
     * @param positionId the position to close
     * @param exitPrice  the exit/fill price
     * @return realized P&amp;L, or {@code BigDecimal.ZERO} if position not found
     */
    public BigDecimal removePosition(String positionId, BigDecimal exitPrice) {
        PositionRisk position = activePositions.remove(positionId);
        if (position == null) {
            log.warn("⚠ Attempted to remove unknown position: {}", positionId);
            return BigDecimal.ZERO;
        }

        // Realized PnL = (exitPrice - entryPrice) × size
        BigDecimal realizedPnl = exitPrice.subtract(position.getEntryPrice(), MC)
                .multiply(position.getSize(), MC);

        // Update daily PnL (atomic CAS loop)
        dailyPnl.accumulateAndGet(realizedPnl, (current, delta) -> current.add(delta, MC));

        // Update balance
        currentBalance.accumulateAndGet(realizedPnl, (current, delta) -> current.add(delta, MC));

        // Update peak balance if new high
        peakBalance.accumulateAndGet(currentBalance.get(),
                (peak, bal) -> bal.compareTo(peak) > 0 ? bal : peak);

        log.info("📤 Position closed: {} exit={} realizedPnl={} dailyPnl={}",
                positionId, exitPrice, realizedPnl, dailyPnl.get());

        return realizedPnl;
    }

    // -----------------------------------------------------------------
    // Tick update (called at 20 Hz)
    // -----------------------------------------------------------------

    /**
     * Updates all open positions with the current market price.
     * Recalculates unrealized P&amp;L, risk levels, and hold times.
     *
     * <p>
     * Designed for zero allocation — mutates existing
     * {@link PositionRisk} objects in place.
     * </p>
     *
     * @param currentPrice the latest market price
     */
    public void updatePositions(BigDecimal currentPrice) {
        Instant now = Instant.now();

        for (PositionRisk pos : activePositions.values()) {
            // Update current price
            pos.setCurrentPrice(currentPrice);

            // Unrealized PnL = (currentPrice - entryPrice) × size
            BigDecimal unrealizedPnl = currentPrice.subtract(pos.getEntryPrice(), MC)
                    .multiply(pos.getSize(), MC);
            pos.setUnrealizedPnl(unrealizedPnl);

            // Risk level based on PnL as % of entry notional
            BigDecimal notional = pos.getEntryPrice().multiply(pos.getSize(), MC);
            if (notional.signum() > 0) {
                BigDecimal pnlPct = unrealizedPnl.divide(notional, MC);
                pos.setRiskLevel(classifyRisk(pnlPct));
            }

            // Time held
            pos.setTimeHeldSeconds(Duration.between(pos.getEntryTime(), now).toSeconds());
        }
    }

    // -----------------------------------------------------------------
    // Risk summary (for logging / dashboarding)
    // -----------------------------------------------------------------

    /**
     * Creates an immutable snapshot of the current risk state.
     *
     * @return a {@link RiskSummary} record
     */
    public RiskSummary getRiskSummary() {
        BigDecimal totalExposure = calculateTotalExposure();
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        RiskLevel worstRisk = RiskLevel.LOW;

        for (PositionRisk pos : activePositions.values()) {
            totalUnrealizedPnl = totalUnrealizedPnl.add(pos.getUnrealizedPnl(), MC);
            if (pos.getRiskLevel().ordinal() > worstRisk.ordinal()) {
                worstRisk = pos.getRiskLevel();
            }
        }

        return new RiskSummary(
                activePositions.size(),
                totalExposure,
                totalUnrealizedPnl,
                dailyPnl.get(),
                currentBalance.get(),
                peakBalance.get(),
                calculateDrawdown(),
                worstRisk);
    }

    // -----------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------

    public int getActivePositionCount() {
        return activePositions.size();
    }

    public BigDecimal getDailyPnl() {
        return dailyPnl.get();
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance.get();
    }

    public RiskLimits getLimits() {
        return limits;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Classifies risk level based on P&amp;L percentage.
     * Uses pre-allocated BigDecimal constants — zero allocation.
     */
    private RiskLevel classifyRisk(BigDecimal pnlPct) {
        if (pnlPct.compareTo(NEG_TEN_PCT) <= 0)
            return RiskLevel.CRITICAL;
        if (pnlPct.compareTo(NEG_FIVE_PCT) <= 0)
            return RiskLevel.HIGH;
        if (pnlPct.compareTo(NEG_TWO_PCT) <= 0)
            return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /**
     * Sums the notional exposure across all active positions.
     */
    private BigDecimal calculateTotalExposure() {
        BigDecimal total = BigDecimal.ZERO;
        for (PositionRisk pos : activePositions.values()) {
            total = total.add(pos.getSize().multiply(pos.getEntryPrice(), MC), MC);
        }
        return total;
    }

    /**
     * Calculates the current drawdown as a decimal (0.0 – 1.0).
     */
    private double calculateDrawdown() {
        BigDecimal peak = peakBalance.get();
        if (peak.signum() <= 0)
            return 0.0;
        BigDecimal bal = currentBalance.get();
        return peak.subtract(bal, MC)
                .divide(peak, MC)
                .doubleValue();
    }
}
