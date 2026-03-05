package com.polymarket.marketmaker.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.polymarket.marketmaker.model.risk.RiskLevel;
import com.polymarket.marketmaker.model.risk.RiskLimits;
import com.polymarket.marketmaker.model.risk.RiskSummary;
import com.polymarket.marketmaker.model.risk.ValidationResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RiskEngine}.
 */
class RiskEngineTest {

    private RiskEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RiskEngine(RiskLimits.DEFAULT, new BigDecimal("100.00"));
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Valid position passes all checks")
    void testValidPositionPasses() {
        ValidationResult result = engine.validateNewPosition(
                new BigDecimal("0.50"), new BigDecimal("0.55"));
        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("Position exceeding max size is rejected")
    void testOversizedPositionRejected() {
        ValidationResult result = engine.validateNewPosition(
                new BigDecimal("2.00"), new BigDecimal("0.55"));
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("exceeds max"));
    }

    @Test
    @DisplayName("Max positions limit enforced")
    void testMaxPositionsEnforced() {
        for (int i = 0; i < 5; i++) {
            engine.addPosition("pos-" + i, new BigDecimal("0.10"), new BigDecimal("0.50"));
        }
        ValidationResult result = engine.validateNewPosition(
                new BigDecimal("0.10"), new BigDecimal("0.50"));
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Max positions"));
    }

    @Test
    @DisplayName("Daily loss limit halts trading")
    void testDailyLossLimitHalts() {
        // Simulate a -$6 daily loss by adding and removing a losing position
        engine.addPosition("loser", new BigDecimal("10.00"), new BigDecimal("1.00"));
        engine.removePosition("loser", new BigDecimal("0.40")); // PnL = -$6.00

        ValidationResult result = engine.validateNewPosition(
                new BigDecimal("0.10"), new BigDecimal("0.50"));
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Daily loss"));
    }

    // -----------------------------------------------------------------
    // Position sizing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Position size is capped at $1.00")
    void testPositionSizeCappedAtOne() {
        // With $100 balance, 100% confidence, 100% score:
        // raw = 100 × 0.02 × 1.0 × 1.0 = $2.00 → capped to $1.00
        BigDecimal size = engine.calculatePositionSize(1.0, 1.0);
        assertEquals(0, size.compareTo(new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("Low confidence → small position")
    void testLowConfidenceSmallPosition() {
        BigDecimal size = engine.calculatePositionSize(0.5, 0.5);
        // raw = 100 × 0.02 × 0.5 × 0.5 = $0.50
        assertEquals(0, size.compareTo(new BigDecimal("0.50")));
    }

    // -----------------------------------------------------------------
    // Position lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("addPosition → removePosition realizes PnL")
    void testPositionLifecycle() {
        engine.addPosition("test-1", new BigDecimal("1.00"), new BigDecimal("0.50"));
        assertEquals(1, engine.getActivePositionCount());

        BigDecimal pnl = engine.removePosition("test-1", new BigDecimal("0.60"));
        // PnL = (0.60 - 0.50) × 1.00 = $0.10
        assertEquals(0, pnl.compareTo(new BigDecimal("0.10")));
        assertEquals(0, engine.getActivePositionCount());
        assertEquals(0, engine.getDailyPnl().compareTo(new BigDecimal("0.10")));
    }

    @Test
    @DisplayName("Removing unknown position returns zero")
    void testRemoveUnknownPositionReturnsZero() {
        BigDecimal pnl = engine.removePosition("ghost", new BigDecimal("1.00"));
        assertEquals(BigDecimal.ZERO, pnl);
    }

    // -----------------------------------------------------------------
    // Update positions
    // -----------------------------------------------------------------

    @Test
    @DisplayName("updatePositions recalculates PnL and risk level")
    void testUpdatePositionsRecalculates() {
        engine.addPosition("pos-1", new BigDecimal("1.00"), new BigDecimal("1.00"));

        // Price drops 12% → should be CRITICAL
        engine.updatePositions(new BigDecimal("0.88"));

        RiskSummary summary = engine.getRiskSummary();
        assertEquals(1, summary.activePositions());
        assertEquals(RiskLevel.CRITICAL, summary.worstRiskLevel());
        assertTrue(summary.totalUnrealizedPnl().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    @DisplayName("Small loss → LOW risk level")
    void testSmallLossIsLowRisk() {
        engine.addPosition("pos-1", new BigDecimal("1.00"), new BigDecimal("1.00"));
        engine.updatePositions(new BigDecimal("0.99")); // -1%

        RiskSummary summary = engine.getRiskSummary();
        assertEquals(RiskLevel.LOW, summary.worstRiskLevel());
    }

    // -----------------------------------------------------------------
    // Risk summary
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getRiskSummary returns correct snapshot")
    void testRiskSummarySnapshot() {
        engine.addPosition("a", new BigDecimal("0.50"), new BigDecimal("0.50"));
        engine.addPosition("b", new BigDecimal("0.80"), new BigDecimal("0.60"));

        RiskSummary summary = engine.getRiskSummary();
        assertEquals(2, summary.activePositions());
        assertEquals(0, summary.dailyPnl().compareTo(BigDecimal.ZERO));
        assertTrue(summary.currentBalance().compareTo(BigDecimal.ZERO) > 0);
    }

    // -----------------------------------------------------------------
    // Drawdown
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Drawdown halts trading when exceeded")
    void testDrawdownHaltsTrading() {
        // Use custom limits with a very high daily loss cap so drawdown fires first
        RiskLimits drawdownLimits = new RiskLimits(
                new BigDecimal("200.00"), // allow large positions
                new BigDecimal("500.00"), // allow large exposure
                10,
                0.15, // 15% drawdown cap
                new BigDecimal("999.00")); // very high daily loss cap — won't trigger

        RiskEngine smallEngine = new RiskEngine(drawdownLimits, new BigDecimal("100.00"));
        // Force balance from $100 → $80 (20% drawdown > 15% limit)
        smallEngine.addPosition("biglose", new BigDecimal("100.00"), new BigDecimal("1.00"));
        smallEngine.removePosition("biglose", new BigDecimal("0.80")); // PnL = -$20

        ValidationResult result = smallEngine.validateNewPosition(
                new BigDecimal("0.10"), new BigDecimal("0.50"));
        assertFalse(result.isValid());
        assertTrue(result.reason().contains("Drawdown"));
    }
}
