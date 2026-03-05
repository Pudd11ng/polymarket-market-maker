package com.polymarket.marketmaker.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * and verifies parsing, endDate filtering, and listener notification.
 * </p>
 */
class MarketDiscoveryServiceTest {

        private RestTemplate mockRestTemplate;
        private List<MarketSwitchListener> listeners;
        private AtomicReference<String> notifiedTokenId;

        /** A time 1 hour in the future — used for active market endDates. */
        private String futureEndDate;

        /** A time 1 hour in the past — used for expired market endDates. */
        private String pastEndDate;

        @BeforeEach
        void setUp() {
                mockRestTemplate = Mockito.mock(RestTemplate.class);
                listeners = new ArrayList<>();
                notifiedTokenId = new AtomicReference<>(null);

                futureEndDate = Instant.now().plus(1, ChronoUnit.HOURS).toString();
                pastEndDate = Instant.now().minus(1, ChronoUnit.HOURS).toString();

                listeners.add(newTokenId -> notifiedTokenId.set(newTokenId));
        }

        // -----------------------------------------------------------------
        // Active market found (endDate in future)
        // -----------------------------------------------------------------

        @Test
        @DisplayName("Finds active market with future endDate and notifies listeners")
        void testActiveMarketNotifiesListeners() {
                String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                                "[\"token-yes-123\", \"token-no-456\"]",
                                false, true, futureEndDate, futureEndDate);

                when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                                .thenReturn(json);

                MarketDiscoveryService service = buildService();
                service.pollForActiveMarket();

                assertEquals("token-yes-123", notifiedTokenId.get());
                assertEquals("token-yes-123", service.getCurrentTokenId());
        }

        // -----------------------------------------------------------------
        // Expired market (endDate in past) → skipped
        // -----------------------------------------------------------------

        @Test
        @DisplayName("Expired market (past endDate) → listeners NOT notified")
        void testExpiredMarketIsSkipped() {
                String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                                "[\"token-expired\", \"token-no\"]",
                                false, true, pastEndDate, pastEndDate);

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
                                "[\"token-yes-123\", \"token-no-456\"]",
                                false, true, futureEndDate, futureEndDate);

                when(mockRestTemplate.getForObject(anyString(), eq(String.class)))
                                .thenReturn(json);

                List<String> notifications = new ArrayList<>();
                listeners.clear();
                listeners.add(notifications::add);

                MarketDiscoveryService service = buildService();

                service.pollForActiveMarket();
                assertEquals(1, notifications.size());

                service.pollForActiveMarket();
                assertEquals(1, notifications.size()); // still 1
        }

        // -----------------------------------------------------------------
        // Closed market → skipped
        // -----------------------------------------------------------------

        @Test
        @DisplayName("Closed market is skipped")
        void testClosedMarketIsSkipped() {
                String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                                "[\"token-closed\", \"token-no\"]",
                                true, true, futureEndDate, futureEndDate);

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
        @DisplayName("parseTokenId extracts first token from clobTokenIds")
        void testParseTokenIdExtractsCorrectly() {
                String json = buildGammaResponse("Bitcoin Up or Down - 15 Minutes",
                                "[\"98765432109876543210\", \"12345678901234567890\"]",
                                false, true, futureEndDate, futureEndDate);

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
                                "10192",
                                listeners,
                                mockRestTemplate);
        }

        /**
         * Builds a Gamma API–shaped JSON response with endDate and startDate.
         */
        private String buildGammaResponse(String title, String clobTokenIds,
                        boolean closed, boolean active,
                        String endDate, String startDate) {
                return String.format(
                                "[{\"title\":\"%s\",\"endDate\":\"%s\",\"startDate\":\"%s\","
                                                + "\"markets\":[{\"conditionId\":\"0xabc\","
                                                + "\"clobTokenIds\":\"%s\",\"closed\":%s,\"active\":%s,"
                                                + "\"endDate\":\"%s\",\"startDate\":\"%s\"}]}]",
                                title, endDate, startDate,
                                clobTokenIds.replace("\"", "\\\""),
                                closed, active, endDate, startDate);
        }
}
