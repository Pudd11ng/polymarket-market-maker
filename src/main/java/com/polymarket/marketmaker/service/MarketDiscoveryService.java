package com.polymarket.marketmaker.service;

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
 * "Bitcoin Up or Down - 15 Minutes" market and extracts its CLOB token ID.
 *
 * <p>
 * When a new token ID is detected, the service orchestrates the safe
 * <b>Pause → Clear → Switch → Resume</b> protocol by notifying all
 * registered {@link MarketSwitchListener}s.
 * </p>
 *
 * <h3>Polling</h3>
 * <p>
 * Runs every 5 minutes by default
 * ({@code polymarket.discovery.poll-interval-ms}).
 * Also runs once at startup via {@code initialDelay = 0}.
 * </p>
 */
@Service
public class MarketDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MarketDiscoveryService.class);

    private final String discoveryUrl;
    private final String eventTitleFilter;
    private final List<MarketSwitchListener> listeners;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** Currently known active token ID. Null until first successful poll. */
    private volatile String currentTokenId;

    @Autowired
    public MarketDiscoveryService(
            @Value("${polymarket.discovery.url:https://gamma-api.polymarket.com/events}") String discoveryUrl,
            @Value("${polymarket.discovery.event-title-filter:Bitcoin Up or Down - 15 Minutes}") String eventTitleFilter,
            List<MarketSwitchListener> listeners) {
        this.discoveryUrl = discoveryUrl;
        this.eventTitleFilter = eventTitleFilter;
        this.listeners = listeners;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();

        log.info("🔍 MarketDiscoveryService initialized — url={}, filter=\"{}\"",
                discoveryUrl, eventTitleFilter);
    }

    // Visible for testing
    MarketDiscoveryService(String discoveryUrl, String eventTitleFilter,
            List<MarketSwitchListener> listeners, RestTemplate restTemplate) {
        this.discoveryUrl = discoveryUrl;
        this.eventTitleFilter = eventTitleFilter;
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
            String fullUrl = discoveryUrl + "?active=true&closed=false&limit=50";
            String response = restTemplate.getForObject(fullUrl, String.class);

            if (response == null || response.isBlank()) {
                log.warn("🔍 Gamma API returned empty response");
                return;
            }

            String tokenId = parseTokenId(response);

            if (tokenId == null) {
                log.debug("🔍 No matching event found for filter \"{}\"", eventTitleFilter);
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
    // JSON parsing
    // -----------------------------------------------------------------

    /**
     * Parses the Gamma API events JSON and extracts the first matching
     * CLOB token ID for the configured event title filter.
     *
     * @param json raw JSON response string
     * @return the "Yes" outcome token ID, or null if no match
     */
    String parseTokenId(String json) {
        try {
            JsonNode events = objectMapper.readTree(json);

            for (JsonNode event : events) {
                String title = event.path("title").asText("");

                if (!title.contains(eventTitleFilter)) {
                    continue;
                }

                JsonNode markets = event.path("markets");
                for (JsonNode market : markets) {
                    // Skip closed markets
                    if (market.path("closed").asBoolean(false)) {
                        continue;
                    }
                    if (!market.path("active").asBoolean(false)) {
                        continue;
                    }

                    // clobTokenIds is a JSON-encoded string: ["<yes>", "<no>"]
                    String clobTokenIdsStr = market.path("clobTokenIds").asText("");
                    if (clobTokenIdsStr.isEmpty()) {
                        continue;
                    }

                    JsonNode tokenIds = objectMapper.readTree(clobTokenIdsStr);
                    if (tokenIds.isArray() && tokenIds.size() > 0) {
                        String tokenId = tokenIds.get(0).asText();
                        log.info("🔍 Found active market: title=\"{}\", tokenId={}",
                                title, tokenId);
                        return tokenId;
                    }
                }
            }
        } catch (Exception e) {
            log.error("🔍 Failed to parse Gamma API response", e);
        }
        return null;
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
