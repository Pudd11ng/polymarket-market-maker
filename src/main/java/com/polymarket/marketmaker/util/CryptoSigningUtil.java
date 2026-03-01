package com.polymarket.marketmaker.util;

import org.springframework.stereotype.Component;

/**
 * Utility class for cryptographic operations needed by Polymarket:
 * - EIP-712 typed-data signing for CLOB order placement
 * - HMAC generation for API authentication headers
 * - Nonce management to prevent replay attacks
 *
 * TODO (Sprint 2): Implement EIP-712 struct hashing and signing
 * using Web3j Credentials.
 */
@Component
public class CryptoSigningUtil {

    // Placeholder — signing logic will be added in Sprint 2.
}
