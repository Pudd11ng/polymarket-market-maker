package com.polymarket.marketmaker.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.polymarket.marketmaker.model.Order;

/**
 * Cryptographic utilities for the Polymarket CLOB:
 * <ol>
 * <li><b>EIP-712</b> typed-data hashing and ECDSA signing of orders</li>
 * <li><b>HMAC-SHA256</b> generation for L2 API authentication headers</li>
 * </ol>
 *
 * <h3>EIP-712 Signing Pipeline</h3>
 * 
 * <pre>
 *   domainSeparator  = keccak256(encode(EIP712DOMAIN_TYPEHASH, name, version, chainId, contract))
 *   structHash       = keccak256(encode(ORDER_TYPEHASH, salt, maker, signer, ...))
 *   digest           = keccak256("\x19\x01" ‖ domainSeparator ‖ structHash)
 *   (v, r, s)        = ecSign(digest, privateKey)
 * </pre>
 *
 * @see <a href="https://eips.ethereum.org/EIPS/eip-712">EIP-712</a>
 */
@Component
public class CryptoSigningUtil {

    private static final Logger log = LoggerFactory.getLogger(CryptoSigningUtil.class);

    // -----------------------------------------------------------------
    // EIP-712 Domain Constants — Polymarket CTF Exchange on Polygon
    // -----------------------------------------------------------------

    private static final String DOMAIN_NAME = "ClobExchange";
    private static final String DOMAIN_VERSION = "1";
    private static final long CHAIN_ID = 137; // Polygon mainnet
    private static final String VERIFYING_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";

    // -----------------------------------------------------------------
    // Type Hashes (keccak256 of the type strings)
    // -----------------------------------------------------------------

    private static final byte[] EIP712DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                    .getBytes(StandardCharsets.UTF_8));

    private static final byte[] ORDER_TYPEHASH = Hash.sha3(
            ("Order(uint256 salt,address maker,address signer,address taker,"
                    + "uint256 tokenId,uint256 makerAmount,uint256 takerAmount,"
                    + "uint256 expiration,uint256 nonce,uint256 feeRateBps,"
                    + "uint8 side,uint8 signatureType)")
                    .getBytes(StandardCharsets.UTF_8));

    /** Cached domain separator — computed once, reused forever. */
    private final byte[] domainSeparator;

    public CryptoSigningUtil() {
        this.domainSeparator = computeDomainSeparator();
        log.info("EIP-712 domain separator computed: 0x{}", Numeric.toHexStringNoPrefix(domainSeparator));
    }

    // -----------------------------------------------------------------
    // Public API — Order Signing
    // -----------------------------------------------------------------

    /**
     * Signs a Polymarket order using EIP-712 and returns the ECDSA signature
     * as a concatenated hex string:
     * {@code r (32 bytes) + s (32 bytes) + v (1 byte)}.
     *
     * @param order       the order to sign (on-chain fields must be populated)
     * @param credentials the wallet credentials holding the private key
     * @return 130-character hex string (65 bytes: r=32 + s=32 + v=1)
     */
    public String signOrder(Order order, Credentials credentials) {
        byte[] digest = computeDigest(order);
        ECKeyPair keyPair = credentials.getEcKeyPair();
        Sign.SignatureData sigData = Sign.signMessage(digest, keyPair, false);

        // Concatenate r + s + v → 65 bytes → 130 hex chars
        byte[] r = sigData.getR();
        byte[] s = sigData.getS();
        byte[] v = sigData.getV();

        byte[] signature = new byte[65];
        System.arraycopy(r, 0, signature, 0, 32);
        System.arraycopy(s, 0, signature, 32, 32);
        signature[64] = v[0];

        String hexSig = Numeric.toHexStringNoPrefix(signature);
        log.debug("Order signed — digest=0x{}, sig=0x{}", Numeric.toHexStringNoPrefix(digest), hexSig);
        return hexSig;
    }

    // -----------------------------------------------------------------
    // Public API — L2 HMAC Authentication
    // -----------------------------------------------------------------

    /**
     * Generates an HMAC-SHA256 signature for Polymarket L2 API authentication.
     *
     * <p>
     * Message format: {@code timestamp + "\n" + method + "\n" + path + "\n" + body}
     * </p>
     *
     * @param secret    Base64-encoded API secret
     * @param timestamp UNIX timestamp string (seconds)
     * @param method    HTTP method (GET, POST, DELETE)
     * @param path      request path (e.g. "/order")
     * @param body      request body (empty string for GET/DELETE without body)
     * @return Base64-encoded HMAC signature
     */
    public String generateHmacSignature(String secret, String timestamp,
            String method, String path, String body) {
        try {
            String message = timestamp + "\n" + method + "\n" + path + "\n" + body;
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] hash = hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate HMAC signature", e);
        }
    }

    // -----------------------------------------------------------------
    // EIP-712 Internals
    // -----------------------------------------------------------------

    /**
     * Computes the EIP-712 digest for an order:
     * {@code keccak256("\x19\x01" ‖ domainSeparator ‖ structHash)}
     */
    byte[] computeDigest(Order order) {
        byte[] structHash = hashOrder(order);

        // "\x19\x01" prefix (2 bytes) + domainSeparator (32 bytes) + structHash (32
        // bytes) = 66 bytes
        byte[] encoded = new byte[66];
        encoded[0] = 0x19;
        encoded[1] = 0x01;
        System.arraycopy(domainSeparator, 0, encoded, 2, 32);
        System.arraycopy(structHash, 0, encoded, 34, 32);

        return Hash.sha3(encoded);
    }

    /**
     * Hashes the Order struct per the ORDER_TYPEHASH.
     *
     * <p>
     * Encodes each field as a 32-byte ABI word and hashes them with keccak256.
     * </p>
     */
    byte[] hashOrder(Order order) {
        // 13 words × 32 bytes = 416 bytes
        byte[] encoded = new byte[13 * 32];
        int offset = 0;

        offset = putBytes32(encoded, offset, ORDER_TYPEHASH);
        offset = putUint256(encoded, offset, order.getSalt());
        offset = putAddress(encoded, offset, order.getMaker());
        offset = putAddress(encoded, offset, order.getSigner());
        offset = putAddress(encoded, offset, order.getTaker());
        offset = putUint256(encoded, offset, new BigInteger(Numeric.cleanHexPrefix(order.getTokenId()), 16));
        offset = putUint256(encoded, offset, order.getMakerAmount());
        offset = putUint256(encoded, offset, order.getTakerAmount());
        offset = putUint256(encoded, offset, BigInteger.valueOf(order.getExpiration()));
        offset = putUint256(encoded, offset, order.getNonce());
        offset = putUint256(encoded, offset, BigInteger.valueOf(order.getFeeRateBps()));
        offset = putUint8(encoded, offset, order.getSide().toUint8());
        putUint8(encoded, offset, order.getSignatureType());

        return Hash.sha3(encoded);
    }

    /**
     * Computes the EIP-712 domain separator for the Polymarket CTF Exchange.
     */
    private byte[] computeDomainSeparator() {
        // 5 words × 32 bytes = 160 bytes
        byte[] encoded = new byte[5 * 32];
        int offset = 0;

        offset = putBytes32(encoded, offset, EIP712DOMAIN_TYPEHASH);
        offset = putBytes32(encoded, offset, Hash.sha3(DOMAIN_NAME.getBytes(StandardCharsets.UTF_8)));
        offset = putBytes32(encoded, offset, Hash.sha3(DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8)));
        offset = putUint256(encoded, offset, BigInteger.valueOf(CHAIN_ID));
        putAddress(encoded, offset, VERIFYING_CONTRACT);

        return Hash.sha3(encoded);
    }

    /**
     * Returns the cached domain separator. Exposed for testing.
     */
    public byte[] getDomainSeparator() {
        return Arrays.copyOf(domainSeparator, domainSeparator.length);
    }

    // -----------------------------------------------------------------
    // ABI encoding helpers
    // -----------------------------------------------------------------

    private static int putBytes32(byte[] dest, int offset, byte[] src) {
        // Left-aligned, 32-byte copy
        System.arraycopy(src, 0, dest, offset, Math.min(src.length, 32));
        return offset + 32;
    }

    private static int putUint256(byte[] dest, int offset, BigInteger value) {
        byte[] bytes = value.toByteArray();
        // BigInteger may produce a leading zero byte for positive numbers; strip it
        int start = (bytes.length > 32) ? bytes.length - 32 : 0;
        int len = Math.min(bytes.length, 32);
        // Right-align in 32-byte slot
        System.arraycopy(bytes, start, dest, offset + 32 - len, len);
        return offset + 32;
    }

    private static int putAddress(byte[] dest, int offset, String address) {
        byte[] addrBytes = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(address));
        // Address is 20 bytes, right-aligned in a 32-byte slot
        System.arraycopy(addrBytes, 0, dest, offset + 32 - addrBytes.length, addrBytes.length);
        return offset + 32;
    }

    private static int putUint8(byte[] dest, int offset, int value) {
        // uint8 is right-aligned in a 32-byte slot
        dest[offset + 31] = (byte) (value & 0xFF);
        return offset + 32;
    }
}
