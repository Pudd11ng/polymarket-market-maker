package com.polymarket.marketmaker.service;

import java.time.Instant;
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

    @Scheduled(fixedRateString = "${polymarket.discovery.poll-interval-ms:300000}", initialDelay = 0)
    public void pollForActiveMarket() {
        try {
            String fullUrl = discoveryUrl + "?active=true&closed=false&series_id=" + seriesId;
            log.debug("🔍 Polling: {}", fullUrl);
            String response = restTemplate.getForObject(fullUrl, String.class);

            if (response == null || response.isBlank()) {
                log.warn("🔍 Gamma API returned empty response");
                return;
            }

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
     * Strategy: iterate all events, filter markets where
     * {@code endDate} is in the future, then pick the one with the
     * latest {@code startDate} (i.e. the current window). Uses a
     * secondary {@code readTree()} to parse the JSON-encoded
     * {@code clobTokenIds} string.
     * </p>
     *
     * @param json raw JSON response string
     * @return the "Yes" outcome token ID, or null if no match
     */
    String parseTokenId(String json) {
        try {
            JsonNode events = objectMapper.readTree(json);
            Instant now = Instant.now();

            String bestTokenId = null;
            String bestTitle = null;
            Instant bestStartDate = Instant.MIN;

            for (JsonNode event : events) {
                String title = event.path("title").asText("");

                JsonNode markets = event.path("markets");
                for (JsonNode market : markets) {
                    // Skip closed markets
                    if (market.path("closed").asBoolean(false)) {
                        log.debug("🔍 Skipping closed market: \"{}\"", title);
                        continue;
                    }
                    if (!market.path("active").asBoolean(false)) {
                        log.debug("🔍 Skipping inactive market: \"{}\"", title);
                        continue;
                    }

                    // --- endDate filter: must be in the future ---
                    String endDateStr = market.path("endDate").asText(
                            event.path("endDate").asText(""));
                    if (!endDateStr.isEmpty()) {
                        Instant endDate = parseInstant(endDateStr);
                        if (endDate != null && endDate.isBefore(now)) {
                            log.debug("🔍 Skipping expired market (endDate={}): \"{}\"",
                                    endDateStr, title);
                            continue;
                        }
                    }

                    // --- startDate: prefer the latest (most current window) ---
                    String startDateStr = market.path("startDate").asText(
                            event.path("startDate").asText(""));
                    Instant startDate = parseInstant(startDateStr);
                    if (startDate == null) {
                        startDate = Instant.MIN;
                    }

                    // --- Extract clobTokenIds ---
                    String clobTokenIdsStr = market.path("clobTokenIds").asText("");
                    if (clobTokenIdsStr.isEmpty()) {
                        log.warn("🔍 Empty clobTokenIds: \"{}\"", title);
                        continue;
                    }

                    JsonNode tokenIds = objectMapper.readTree(clobTokenIdsStr);
                    if (!tokenIds.isArray() || tokenIds.size() == 0) {
                        log.warn("🔍 Invalid clobTokenIds array: \"{}\"", title);
                        continue;
                    }

                    String tokenId = tokenIds.get(0).asText();

                    // Keep track of the market with the latest startDate
                    if (startDate.isAfter(bestStartDate)) {
                        bestStartDate = startDate;
                        bestTokenId = tokenId;
                        bestTitle = title;
                    }
                }
            }

            if (bestTokenId != null) {
                log.info("🎯 Found active market in Series {}: {} | Token ID: {}",
                        seriesId, bestTitle, bestTokenId);
            }
            return bestTokenId;

        } catch (Exception e) {
            log.error("🔍 Failed to parse Gamma API response", e);
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
