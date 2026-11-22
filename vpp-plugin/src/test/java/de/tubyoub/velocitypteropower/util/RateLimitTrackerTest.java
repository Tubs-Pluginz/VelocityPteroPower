/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelType;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitTrackerTest {

    private ConfigurationManager config;
    private ComponentLogger logger;
    private RateLimitTracker tracker;

    @BeforeEach
    void setUp() {
        config = mock(ConfigurationManager.class);
        logger = mock(ComponentLogger.class);
        when(config.getPanelType()).thenReturn(PanelType.pterodactyl);
        when(config.getRateLimitProbeFallbackSeconds()).thenReturn(60);
        when(config.getRateLimitBackgroundPauseThreshold()).thenReturn(5);
        when(config.isPrintRateLimit()).thenReturn(false);
        tracker = new RateLimitTracker(logger, config);
    }

    @Test
    void optimisticConsumeAndRestoreKeepBounds() {
        assertThat(tracker.getRemainingRequests()).isEqualTo(60);
        tracker.consumeOne();
        assertThat(tracker.getRemainingRequests()).isEqualTo(59);
        tracker.restoreOne();
        assertThat(tracker.getRemainingRequests()).isEqualTo(60);
        tracker.restoreOne();
        assertThat(tracker.getRemainingRequests()).isEqualTo(60);
    }

    @Test
    void updatesFromResponseHeadersAndRecoversAfterResetEpoch() {
        HttpResponse<?> exhausted = mockResponse(Map.of(
                "x-ratelimit-limit", List.of("120"),
                "x-ratelimit-remaining", List.of("0"),
                "x-ratelimit-reset", List.of(String.valueOf(Instant.now().getEpochSecond() + 30))
        ));
        tracker.updateRateLimitInfo(exhausted);

        assertThat(tracker.getRateLimit()).isEqualTo(120);
        assertThat(tracker.getRemainingRequests()).isZero();
        assertThat(tracker.canMakeRequest(PanelRequestPriority.BACKGROUND)).isFalse();
        assertThat(tracker.canMakeRequest(PanelRequestPriority.PLAYER)).isFalse();

        // Simulate reset time already elapsed
        HttpResponse<?> expiredReset = mockResponse(Map.of(
                "x-ratelimit-limit", List.of("120"),
                "x-ratelimit-remaining", List.of("0"),
                "x-ratelimit-reset", List.of(String.valueOf(Instant.now().getEpochSecond() - 5))
        ));
        tracker.updateRateLimitInfo(expiredReset);

        assertThat(tracker.canMakeRequest(PanelRequestPriority.PLAYER)).isTrue();
        assertThat(tracker.getRemainingRequests()).isEqualTo(120);
    }

    @Test
    void supportsRetryAfterHeaderWhenResetHeaderMissing() {
        HttpResponse<?> retryAfterResp = mockResponse(Map.of(
                "x-ratelimit-remaining", List.of("0"),
                "retry-after", List.of("15")
        ));
        tracker.updateRateLimitInfo(retryAfterResp);

        assertThat(tracker.canMakeRequest(PanelRequestPriority.BACKGROUND)).isFalse();
        assertThat(tracker.secondsUntilReset()).isBetween(1L, 16L);
    }

    @Test
    void schedulerPausesBackgroundUnderRatePressureOrActiveWaiters() {
        VelocityPteroPower plugin = mock(VelocityPteroPower.class);
        FilteredComponentLogger fl = mock(FilteredComponentLogger.class);
        Map<String, Set<UUID>> waiting = new ConcurrentHashMap<>();
        when(plugin.getFilteredLogger()).thenReturn(fl);
        when(plugin.getWaitingPlayers()).thenReturn(waiting);

        PanelRequestScheduler scheduler = new PanelRequestScheduler(plugin, tracker, config);
        assertThat(scheduler.shouldPauseBackground()).isFalse();
        assertThat(scheduler.canDispatch(PanelRequestPriority.BACKGROUND)).isTrue();

        // Active waiting player should pause background prefetch
        waiting.put("survival", Set.of(UUID.randomUUID()));
        assertThat(scheduler.shouldPauseBackground()).isTrue();
        assertThat(scheduler.canDispatch(PanelRequestPriority.BACKGROUND)).isFalse();
        assertThat(scheduler.canDispatch(PanelRequestPriority.PLAYER)).isTrue();

        // Clear waiters, drop remaining to pause threshold
        waiting.clear();
        HttpResponse<?> low = mockResponse(Map.of("x-ratelimit-remaining", List.of("5")));
        tracker.updateRateLimitInfo(low);
        assertThat(scheduler.shouldPauseBackground()).isTrue();
        assertThat(scheduler.canDispatch(PanelRequestPriority.PLAYER)).isTrue();
    }

    @Test
    void doesNotSelfExhaustWhenPanelOmitsRateLimitHeaders() {
        HttpResponse<?> noHeadersOk = mockResponse(Map.of());
        when(noHeadersOk.statusCode()).thenReturn(200);
        for (int i = 0; i < 100; i++) {
            assertThat(tracker.canMakeRequest(PanelRequestPriority.PLAYER)).isTrue();
            tracker.consumeOne();
            tracker.updateRateLimitInfo(noHeadersOk);
        }
        assertThat(tracker.getRemainingRequests()).isEqualTo(60);

        // Actual 429 without X-RateLimit-Remaining still backs off cleanly
        HttpResponse<?> tooMany = mockResponse(Map.of("retry-after", List.of("10")));
        when(tooMany.statusCode()).thenReturn(429);
        tracker.consumeOne();
        tracker.updateRateLimitInfo(tooMany);
        assertThat(tracker.getRemainingRequests()).isZero();
        assertThat(tracker.canMakeRequest(PanelRequestPriority.BACKGROUND)).isFalse();
    }

    private static HttpResponse<?> mockResponse(Map<String, List<String>> headers) {
        HttpResponse<?> resp = mock(HttpResponse.class);
        when(resp.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        return resp;
    }
}
