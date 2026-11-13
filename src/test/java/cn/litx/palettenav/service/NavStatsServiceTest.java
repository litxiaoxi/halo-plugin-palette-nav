package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.extension.NavVisitStat;
import cn.litx.palettenav.extension.NavVisitVisitor;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NavStatsServiceTest {

    @Test
    void resolveClientIpUsesRightmostForwardedAddressFromTrustedProxy() {
        assertEquals("198.51.100.7",
            NavStatsService.resolveClientIp(" 203.0.113.9 , 198.51.100.7", "10.0.0.2",
                "10.0.0.3", "10.0.0.3"));
        // A same-host proxy connects over loopback and needs no configuration.
        assertEquals("203.0.113.9",
            NavStatsService.resolveClientIp("203.0.113.9", "10.0.0.2", "::1", ""));
    }

    @Test
    void resolveClientIpIgnoresForwardedHeadersFromPublicPeers() {
        // A direct public peer must not be able to forge its identity with headers.
        assertEquals("198.51.100.4",
            NavStatsService.resolveClientIp("203.0.113.9", "10.0.0.2", "198.51.100.4", ""));
    }

    @Test
    void resolveClientIpIgnoresForwardedHeadersFromUntrustedPrivatePeers() {
        // On an intranet deployment every visitor is private, so a private peer that
        // is not a configured proxy must not be able to claim another address.
        assertEquals("192.168.1.20",
            NavStatsService.resolveClientIp("10.0.0.99", "10.0.0.98", "192.168.1.20", ""));
    }

    @Test
    void resolveClientIpTrustsConfiguredProxyAddresses() {
        assertEquals("203.0.113.9",
            NavStatsService.resolveClientIp("203.0.113.9", null, "192.168.1.20",
                " 192.168.1.20 , 2001:4860:4860::8888 "));
    }

    @Test
    void resolveClientIpFallsBackToRealIpAndRemoteAddress() {
        assertEquals("10.0.0.2",
            NavStatsService.resolveClientIp("  ", " 10.0.0.2 ", "10.0.0.3", "10.0.0.3"));
        assertEquals("10.0.0.3",
            NavStatsService.resolveClientIp(null, "", " 10.0.0.3 ", ""));
        assertEquals("unknown", NavStatsService.resolveClientIp(null, null, "", ""));
    }

    @Test
    void visitorHashIsStableButChangesAcrossDaysAndInputs() {
        var salt = "0123456789abcdef";
        var first = NavStatsService.visitorHash(salt, "203.0.113.9", "Mozilla/5.0");
        var second = NavStatsService.visitorHash(salt, "203.0.113.9", "Mozilla/5.0");
        var otherSalt = NavStatsService.visitorHash("fedcba9876543210", "203.0.113.9",
            "Mozilla/5.0");
        var otherIp = NavStatsService.visitorHash(salt, "203.0.113.10", "Mozilla/5.0");
        var otherAgent = NavStatsService.visitorHash(salt, "203.0.113.9", "curl/8.0");

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertNotEquals(first, otherSalt);
        assertNotEquals(first, otherIp);
        assertNotEquals(first, otherAgent);
    }

    @Test
    void buildStatsFillsMissingDaysAndSumsAllTimePv() {
        var today = LocalDate.of(2026, 11, 7);
        var stats = List.of(
            stat("2026-11-01", 120, 8),
            stat("2026-11-06", 1500, 14),
            stat("2026-11-07", 397, 3),
            stat("2026-10-30", 1000, 20)
        );

        var result = NavStatsService.buildStats(today, stats, 177);

        assertEquals(177, result.linkCount());
        assertEquals(397, result.todayPv());
        assertEquals(3, result.todayUv());
        assertEquals(3017, result.totalPv());
        assertEquals(7, result.daily().size());
        assertEquals("2026-11-01", result.daily().get(0).date());
        assertEquals(120, result.daily().get(0).pv());
        assertEquals(0, result.daily().get(1).pv());
        assertEquals(1500, result.daily().get(5).pv());
        assertEquals(14, result.daily().get(5).uv());
        assertEquals("2026-11-07", result.daily().get(6).date());
        assertEquals(397, result.daily().get(6).pv());
    }

    @Test
    void buildStatsHandlesEmptyAndMalformedCounters() {
        var today = LocalDate.of(2026, 11, 7);
        var missingSpec = new NavVisitStat();
        var blankDate = stat("  ", 10, 2);
        var nullCounters = new NavVisitStat();
        var nullSpec = new NavVisitStat.Spec();
        nullSpec.setDate("2026-11-07");
        nullCounters.setSpec(nullSpec);

        var result = NavStatsService.buildStats(today,
            List.of(missingSpec, blankDate, nullCounters), 0);

        assertEquals(0, result.linkCount());
        assertEquals(0, result.todayPv());
        assertEquals(0, result.todayUv());
        assertEquals(0, result.totalPv());
        assertEquals(7, result.daily().size());
        assertEquals("2026-11-07", result.daily().getLast().date());
    }

    @Test
    void buildFooterStatsPicksTodayAndSumsAllTimePv() {
        var today = LocalDate.of(2026, 11, 7);
        var result = NavStatsService.buildFooterStats(today, List.of(
            stat("2026-10-30", 1000, 20),
            stat("2026-11-07", 397, 3)
        ));

        assertEquals(397, result.todayPv());
        assertEquals(3, result.todayUv());
        assertEquals(1397, result.totalPv());
    }

    @Test
    void buildFooterStatsHandlesMalformedCountersAndMissingToday() {
        var today = LocalDate.of(2026, 11, 7);
        var result = NavStatsService.buildFooterStats(today,
            List.of(new NavVisitStat(), stat("2026-11-06", 5, 1)));

        assertEquals(0, result.todayPv());
        assertEquals(0, result.todayUv());
        assertEquals(5, result.totalPv());
    }

    @Test
    void buildStatsIncludesDaysOutsideTheWindowInTheAllTimeTotal() {
        var today = LocalDate.of(2026, 11, 7);
        var result = NavStatsService.buildStats(today,
            List.of(stat("2020-01-01", 7, 1), stat("2026-11-07", 3, 1)), 5);

        assertEquals(10, result.totalPv());
        assertEquals(3, result.todayPv());
        // The window starts six days before today.
        assertEquals("2026-11-01", result.daily().getFirst().date());
        assertEquals(0, result.daily().getFirst().pv());
    }

    @Test
    void resolveClientIpIgnoresMalformedForwardedEntries() {
        // Host names are not trusted as proxy peers and are not used raw.
        assertEquals("proxy.local",
            NavStatsService.resolveClientIp("203.0.113.9", null, "proxy.local", ""));
        // With no socket address the headers are the only information left.
        assertEquals("203.0.113.9",
            NavStatsService.resolveClientIp(" , 203.0.113.9 ", null, null, ""));
        assertEquals("203.0.113.9",
            NavStatsService.resolveClientIp("", "203.0.113.9", null, ""));
    }

    @Test
    void visitorHashToleratesNullInputs() {
        var first = NavStatsService.visitorHash(null, null, null);
        var second = NavStatsService.visitorHash(null, null, null);
        assertEquals(first, second);
        assertEquals(16, first.length());
    }

    @Test
    void buffersCountersAndWritesTheDailyStatOncePerFlush() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new NavStatsService(client);
        var today = LocalDate.now();
        var stat = new NavVisitStat();
        var metadata = new Metadata();
        metadata.setName("nav-visit-" + today);
        stat.setMetadata(metadata);
        var spec = new NavVisitStat.Spec();
        spec.setDate(today.toString());
        spec.setSalt("0123456789abcdef");
        spec.setPv(0L);
        spec.setUv(0L);
        stat.setSpec(spec);

        when(client.fetch(eq(NavVisitStat.class), anyString())).thenReturn(Mono.just(stat));
        when(client.create(any(NavVisitVisitor.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(NavVisitStat.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.listAll(eq(NavVisitVisitor.class), any(ListOptions.class),
            any(Sort.class))).thenReturn(Flux.empty());

        for (int index = 0; index < 3; index++) {
            service.recordVisit(null, null, "203.0.113.9", "Mozilla/5.0", "").block();
        }

        // Three renders only touch the visitor model; the daily counter is untouched.
        verify(client, never()).update(any(NavVisitStat.class));
        service.flush().block();

        verify(client, times(1)).update(any(NavVisitStat.class));
        assertEquals(3L, stat.getSpec().getPv());
        assertEquals(3L, stat.getSpec().getUv());
    }

    @Test
    void failedFlushRestoresDeltasAndKeepsThePastDateEntry() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new NavStatsService(client);
        // Cross-midnight batches are buffered under the date the visit happened on,
        // so the retry path has to keep that (already past) entry alive.
        var yesterday = LocalDate.now().minusDays(1);
        var daily = new NavStatsService.DailyCounters("salt", 0L);
        daily.pendingPv.set(5);
        daily.pendingUv.set(2);
        var counters = new ConcurrentHashMap<LocalDate, NavStatsService.DailyCounters>();
        counters.put(yesterday, daily);
        ReflectionTestUtils.setField(service, "counters", counters);
        when(client.listAll(eq(NavVisitVisitor.class), any(ListOptions.class),
            any(Sort.class))).thenReturn(Flux.empty());
        when(client.fetch(eq(NavVisitStat.class), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("store unavailable")));

        service.flush().block();

        assertEquals(5L, daily.pendingPv.get());
        assertEquals(2L, daily.pendingUv.get());
        assertTrue(counters.containsKey(yesterday));

        // A later flush writes the restored deltas instead of losing them.
        var stat = stat(yesterday.toString(), 10L, 3L);
        when(client.fetch(eq(NavVisitStat.class), anyString())).thenReturn(Mono.just(stat));
        when(client.update(any(NavVisitStat.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.flush().block();

        assertEquals(0L, daily.pendingPv.get());
        assertEquals(0L, daily.pendingUv.get());
        assertEquals(15L, stat.getSpec().getPv());
        assertEquals(5L, stat.getSpec().getUv());
        assertFalse(counters.containsKey(yesterday));
    }

    @Test
    void cancelledFlushRestoresPendingDeltas() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new NavStatsService(client);
        var yesterday = LocalDate.now().minusDays(1);
        var daily = new NavStatsService.DailyCounters("salt", 0L);
        daily.pendingPv.set(4);
        daily.pendingUv.set(1);
        // stop() disposes the flush loop, which cancels an in-flight write after the
        // pending counters were already swapped out; they must come back.
        when(client.fetch(eq(NavVisitStat.class), anyString())).thenReturn(Mono.never());

        var subscription = service.flushCounters(yesterday, daily).subscribe();
        subscription.dispose();

        assertEquals(4L, daily.pendingPv.get());
        assertEquals(1L, daily.pendingUv.get());
    }

    private NavVisitStat stat(String date, long pv, long uv) {
        var extension = new NavVisitStat();
        var spec = new NavVisitStat.Spec();
        spec.setDate(date);
        spec.setPv(pv);
        spec.setUv(uv);
        extension.setSpec(spec);
        return extension;
    }
}