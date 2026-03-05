package com.polymarket.marketmaker.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Polls the Polymarket Gamma API to discover the currently active
 * Bitcoin 15-minute market using series-based pre-filtering.
 *
 * <p>
 * Uses {@code series_id} (default {@code 10192}) so the API returns
 * only Bitcoin 15-minute "Up or Down" events. The service picks the
 * market with the latest {@code startDate} whose {@code endDate} is
 * still in the future — i.e., the current 15-minute window.
 * </p>
 *
 * <p>
 * When a new token ID is detected, the service orchestrates the safe
 * <b>Pause → Clear → Switch → Resume</b> protocol by notifying all
 * registered {@link MarketSwitchListener}s.
 * </p>
 */
@Service
public class MarketDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MarketDiscoveryService.class);

    private final String discoveryUrl;
    private final String seriesId;
    private final List<MarketSwitchListener> listeners;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** Currently known active token ID. Null until first successful poll. */
    private volatile String currentTokenId;

    @Autowired
    public MarketDiscoveryService(
            @Value("${polymarket.discovery.url:https://gamma-api.polymarket.com/events}") String discoveryUrl,
            @Value("${polymarket.discovery.series-id:10192}") String seriesId,
            List<MarketSwitchListener> listeners) {
        this.discoveryUrl = discoveryUrl;
        this.seriesId = seriesId;
        this.listeners = listeners;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();

        log.info("🔍 MarketDiscoveryService initialized — url={}, seriesId={}",
                discoveryUrl, seriesId);
    }

    // Visible for testing
    MarketDiscoveryService(String discoveryUrl, String seriesId,
            List<MarketSwitchListener> listeners, RestTemplate restTemplate) {
        this.discoveryUrl = discoveryUrl;
        this.seriesId = seriesId;
        this.listeners = listeners;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------
    // Scheduled polling — every 5 minutes
    // -----------------------------------------------------------------

    @Scheduled(fixedRateString = "${polymarket.discovery.poll-interval-ms:60000}", initialDelay = 0)
    public void pollForActiveMarket() {
        try {
            // Log current ET time for timezone clarity
            ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
            log.info("🕒 Current Time in New York (ET): {}", nowET.format(DateTimeFormatter.ofPattern("HH:mm:ss z")));

            String fullUrl = discoveryUrl + "?active=true&closed=false&series_id=" + seriesId;
            log.debug("🔍 Polling: {}", fullUrl);
            String response = restTemplate.getForObject(fullUrl, String.class);

            if (response == null || response.isBlank()) {
                log.warn("🔍 Gamma API returned empty response");
                return;
            }

            log.info("📡 Raw Gamma API Data: {}", response);

            String tokenId = parseTokenId(response);

            if (tokenId == null) {
                log.warn("🔍 No active Bitcoin 15m markets found via series_id={}.", seriesId);
                return;
            }

            if (tokenId.equals(currentTokenId)) {
                log.trace("🔍 Token unchanged ({}), no switch needed", tokenId);
                return;
            }

            // New market detected — execute Pause → Clear → Switch → Resume
            String oldTokenId = currentTokenId;
            currentTokenId = tokenId;

            log.info("🔄 Market switch detected: {} → {}", oldTokenId, tokenId);
            notifyListeners(tokenId);

        } catch (Exception e) {
            log.error("🔍 Failed to poll Gamma API", e);
        }
    }

    // -----------------------------------------------------------------
    // JSON parsing — endDate / startDate aware
    // -----------------------------------------------------------------

    /**
     * Parses the Gamma API events JSON and extracts the CLOB token ID
     * for the <b>current</b> 15-minute window.
     *
     * <p>
     * <b>Strategy:</b>
     * </p>
     * <ol>
     * <li><b>Live window</b>: Find a market where startDate &le; now AND endDate
     * &gt; now.
     * If found, return immediately.</li>
     * <li><b>Fallback</b>: If no live window exists (gap between windows), pick the
     * market with the earliest future startDate (the next upcoming window).</li>
     * </ol>
     *
     * @param json raw JSON response string
     * @return the "Yes" outcome token ID, or null if no match
     */
    String parseTokenId(String json) {
        try {
            JsonNode events = objectMapper.readTree(json);
            Instant now = Instant.now();

            // Fallback: soonest future market
            String fallbackTokenId = null;
            String fallbackTitle = null;
            Instant fallbackStartDate = Instant.MAX;

            for (JsonNode event : events) {
                String title = event.path("title").asText("");

                JsonNode markets = event.path("markets");
                for (JsonNode market : markets) {
                    // Skip closed / inactive
                    if (market.path("closed").asBoolean(false)) {
                        continue;
                    }
                    if (!market.path("active").asBoolean(false)) {
                        continue;
                    }

                    // Parse dates
                    String endDateStr = market.path("endDate").asText(
                            event.path("endDate").asText(""));
                    Instant endDate = parseInstant(endDateStr);
                    if (endDate == null || endDate.isBefore(now)) {
                        log.debug("🔍 Skipping expired (endDate={}): \"{}\"", endDateStr, title);
                        continue;
                    }

                    String startDateStr = market.path("startDate").asText(
                            event.path("startDate").asText(""));
                    Instant startDate = parseInstant(startDateStr);

                    // Extract token ID
                    String tokenId = extractFirstTokenId(market, title);
                    if (tokenId == null)
                        continue;

                    // --- PRIORITY 1: Live window (startDate <= now AND endDate > now) ---
                    if (startDate != null && !startDate.isAfter(now)) {
                        log.info("🎯 LIVE 15m window in Series {}: {} | Token ID: {}",
                                seriesId, title, tokenId);
                        return tokenId; // Immediately return — this is THE market
                    }

                    // --- PRIORITY 2: Track soonest future market as fallback ---
                    if (startDate != null && startDate.isBefore(fallbackStartDate)) {
                        fallbackStartDate = startDate;
                        fallbackTokenId = tokenId;
                        fallbackTitle = title;
                    }
                }
            }

            // No live window — use fallback if available
            if (fallbackTokenId != null) {
                log.info("⏳ No live window — using next upcoming market in Series {}: {} (starts {}) | Token ID: {}",
                        seriesId, fallbackTitle, fallbackStartDate, fallbackTokenId);
                return fallbackTokenId;
            }

            log.info("🔍 No live or upcoming markets found in Series {}", seriesId);
            return null;

        } catch (Exception e) {
            log.error("🔍 Failed to parse Gamma API response", e);
        }
        return null;
    }

    /**
     * Extracts the first token ID from a market's clobTokenIds field.
     * Returns null if the field is missing or empty.
     */
    private String extractFirstTokenId(JsonNode market, String title) {
        try {
            String clobTokenIdsStr = market.path("clobTokenIds").asText("");
            if (clobTokenIdsStr.isEmpty()) {
                log.warn("🔍 Empty clobTokenIds: \"{}\"", title);
                return null;
            }
            JsonNode tokenIds = objectMapper.readTree(clobTokenIdsStr);
            if (tokenIds.isArray() && tokenIds.size() > 0) {
                return tokenIds.get(0).asText();
            }
            log.warn("🔍 Invalid clobTokenIds array: \"{}\"", title);
        } catch (Exception e) {
            log.warn("🔍 Failed to parse clobTokenIds for: \"{}\"", title);
        }
        return null;
    }

    /**
     * Safely parses an ISO-8601 instant string. Returns null on failure.
     */
    private Instant parseInstant(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            log.trace("Could not parse date: {}", dateStr);
            return null;
        }
    }

    // -----------------------------------------------------------------
    // Listener notification
    // -----------------------------------------------------------------

    private void notifyListeners(String newTokenId) {
        for (MarketSwitchListener listener : listeners) {
            try {
                listener.onMarketSwitch(newTokenId);
                log.debug("🔔 Notified {} of market switch to {}",
                        listener.getClass().getSimpleName(), newTokenId);
            } catch (Exception e) {
                log.error("🔔 Listener {} failed on market switch",
                        listener.getClass().getSimpleName(), e);
            }
        }
    }

    // -----------------------------------------------------------------
    // Accessors (for testing)
    // -----------------------------------------------------------------

    public String getCurrentTokenId() {
        return currentTokenId;
    }
}
