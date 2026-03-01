package com.polymarket.marketmaker.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MarketDiscoveryService}.
 *
 * <p>
 * Mocks the {@link RestTemplate} to return canned Gamma API responses
 * and verifies parsing, filtering, and listener notification logic.
 * </p>
 */
class MarketDiscoveryServiceTest {

    private RestTemplate mockRestTemplate;
    private List<MarketSwitchListener> listeners;
    private AtomicReference<String> notifiedTokenId;

    @BeforeEach
    void setUp() {
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        listeners = new ArrayList<>();
        notifiedTokenId = new AtomicReference<>(null);

        // Simple listener that records the notified token ID
        listeners.add(newTokenId -> notifiedTokenId.set(newTokenId));
    }

    // -----------------------------------------------------------------
    // Matching event found
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Finds matching event and notifies listeners")
    void testMatchingEventNotifiesListeners() {
        String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                "[\"token-yes-123\", \"token-no-456\"]", false, true);

        when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json);

        MarketDiscoveryService service = buildService();
        service.pollForActiveMarket();

        assertEquals("token-yes-123", notifiedTokenId.get());
        assertEquals("token-yes-123", service.getCurrentTokenId());
    }

    // -----------------------------------------------------------------
    // No matching event
    // -----------------------------------------------------------------

    @Test
    @DisplayName("No matching event → listeners NOT notified")
    void testNoMatchingEventDoesNotNotify() {
        String json = buildGammaResponse("Some Other Event",
                "[\"token-111\", \"token-222\"]", false, true);

        when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json);

        MarketDiscoveryService service = buildService();
        service.pollForActiveMarket();

        assertNull(notifiedTokenId.get());
        assertNull(service.getCurrentTokenId());
    }

    // -----------------------------------------------------------------
    // Same token ID → no redundant switch
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Same token ID as before → no redundant switch")
    void testSameTokenIdDoesNotSwitch() {
        String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                "[\"token-yes-123\", \"token-no-456\"]", false, true);

        when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json);

        // Count notifications
        List<String> notifications = new ArrayList<>();
        listeners.clear();
        listeners.add(notifications::add);

        MarketDiscoveryService service = buildService();

        // First poll → should notify
        service.pollForActiveMarket();
        assertEquals(1, notifications.size());

        // Second poll with same token → should NOT notify again
        service.pollForActiveMarket();
        assertEquals(1, notifications.size()); // still 1
    }

    // -----------------------------------------------------------------
    // Skip closed markets
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Closed market is skipped even if title matches")
    void testClosedMarketIsSkipped() {
        String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                "[\"token-closed\", \"token-no\"]", true, true);

        when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(json);

        MarketDiscoveryService service = buildService();
        service.pollForActiveMarket();

        assertNull(notifiedTokenId.get());
    }

    // -----------------------------------------------------------------
    // parseTokenId directly
    // -----------------------------------------------------------------

    @Test
    @DisplayName("parseTokenId extracts first token from clobTokenIds string")
    void testParseTokenIdExtractsCorrectly() {
        String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                "[\"98765432109876543210\", \"12345678901234567890\"]", false, true);

        MarketDiscoveryService service = buildService();
        String tokenId = service.parseTokenId(json);

        assertEquals("98765432109876543210", tokenId);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private MarketDiscoveryService buildService() {
        return new MarketDiscoveryService(
                "https://gamma-api.polymarket.com/events",
                "Bitcoin Up or Down - 15 Minutes",
                listeners,
                mockRestTemplate);
    }

    /**
     * Builds a minimal Gamma API–shaped JSON response.
     */
    private String buildGammaResponse(String title, String clobTokenIds,
            boolean closed, boolean active) {
        return String.format(
                "[{\"title\":\"%s\",\"markets\":[{\"conditionId\":\"0xabc\","
                        + "\"clobTokenIds\":\"%s\",\"closed\":%s,\"active\":%s}]}]",
                title, clobTokenIds.replace("\"", "\\\""), closed, active);
    }
}
