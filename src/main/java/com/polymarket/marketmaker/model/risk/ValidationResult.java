package com.polymarket.marketmaker.model.risk;

/**
 * Outcome of a pre-trade risk check.
 *
 * @param isValid whether the proposed trade passes all risk checks
 * @param reason  human-readable explanation (empty string when valid)
 */
public record ValidationResult(boolean isValid, String reason) {

    /** Pre-allocated singleton for the common "pass" case — zero allocation. */
    public static final ValidationResult PASS = new ValidationResult(true, "");

    /** Factory for failure results. */
    public static ValidationResult fail(String reason) {
        return new ValidationResult(false, reason);
    }
}
