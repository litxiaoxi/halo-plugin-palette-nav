package cn.litx.palettenav.service;

import cn.litx.palettenav.dto.NavDailyVisit;
import cn.litx.palettenav.dto.NavDashboardStats;
import cn.litx.palettenav.dto.NavFooterStats;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavVisitStat;
import cn.litx.palettenav.extension.NavVisitVisitor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Records and aggregates visit statistics of the public {@code /nav} page.
 *
 * <p>Distinct visitors are deduplicated per day: each new visitor creates one small
 * {@link NavVisitVisitor} resource named {@code nav-visitors-yyyy-MM-dd-&lt;hash&gt;},
 * so recording a view is a constant-time create instead of rewriting a growing
 * per-day hash list. Halo's duplicate-name error is the signal that this visitor was
 * already counted, which keeps the deduplication atomic under concurrent requests.</p>
 *
 * <p>The daily {@link NavVisitStat} counters are not written on every view. Page views
 * and distinct visitors are accumulated in memory and flushed in one update every
 * {@link #FLUSH_INTERVAL}, so a burst of traffic rewrites the same daily resource a few
 * times per minute instead of once per request. Flushing keeps the deltas and restores
 * them if the write fails or is cancelled, and the final flush runs when the plugin
 * stops.</p>
 *
 * <p>Hashes are salted with a per-day random salt stored on the daily
 * {@link NavVisitStat}. Without the salt the same IP + User-Agent produced the same hash
 * every day, so anyone able to read the extension data could join two days and follow a
 * visitor. The salt only has to survive the day it is used for; visitor resources older
 * than yesterday are removed by {@link #cleanupExpiredVisitors(LocalDate)} using the
 * per-day label index instead of scanning the whole model.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavStatsService {

    private static final int VISITOR_HASH_LENGTH = 16;
    private static final int SALT_BYTES = 16;
    private static final int MAX_VISITORS_PER_DAY = 20_000;
    private static final int MAX_CONFLICT_RETRIES = 5;
    private static final int CLEANUP_CONCURRENCY = 8;
    /**
     * Visitor resources are only read for same-day deduplication. Keeping yesterday
     * covers requests racing a midnight boundary or a node whose clock drifts slightly.
     */
    private static final int VISITOR_RETENTION_DAYS = 1;
    /** How often buffered PV/UV counters are written to the daily stat resource. */
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);
    /**
     * The footer counters only change when a flush lands, so the aggregate read by
     * every {@code /nav} render is cached for the flush interval instead of listing
     * the daily stats on each page view.
     */
    private static final Duration FOOTER_CACHE_TTL = FLUSH_INTERVAL;
    /** Upper bound for the final flush while the plugin is stopping. */
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(3);
    private static final String STAT_NAME_PREFIX = "nav-visit-";
    private static final String VISITOR_NAME_PREFIX = "nav-visitors-";
    private static final String VISITOR_DATE_LABEL = "palette-nav.halo.run/visit-date";

    /** Only socket addresses shaped like an IP literal are parsed, never host names. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Halo's DuplicateNameException lives in the application jar, not in the
     * {@code run.halo.app:api} compile classpath, so it is matched by class name.
     *
     * <p>Revisit this on every Halo upgrade: if the exception moves into the API jar,
     * prefer an {@code instanceof} check; if it is renamed or wrapped differently,
     * duplicate visitor creates would stop being detected and UV would silently
     * over-count. The cause chain is checked as well because extension-store errors
     * may be wrapped.</p>
     */
    private static final String DUPLICATE_NAME_EXCEPTION =
        "run.halo.app.infra.exception.DuplicateNameException";

    private final ReactiveExtensionClient client;

    /** Guards the once-per-day visitor cleanup and the "UV capped" warning. */
    private final AtomicReference<LocalDate> lastVisitorCleanup = new AtomicReference<>();
    private final AtomicReference<LocalDate> lastLimitWarning = new AtomicReference<>();

    /**
     * Today's salt plus buffered counters, keyed by date. An entry stays until its
     * deltas have been written, so a flush that crosses midnight still lands in the
     * correct daily resource.
     */
    private final Map<LocalDate, DailyCounters> counters = new ConcurrentHashMap<>();

    /** Last footer aggregate; entries for another date are ignored across midnight. */
    private volatile FooterSnapshot footerSnapshot;

    private Disposable flushSubscription;

    /**
     * Records one page view. Safe to call from a fire-and-forget subscription; the
     * counters are buffered and written by the flush loop.
     */
    public Mono<Void> recordVisit(String forwardedFor, String realIp, String remoteAddress,
        String userAgent, String trustedProxyIps) {
        var today = LocalDate.now();
        var clientIp = resolveClientIp(forwardedFor, realIp, remoteAddress, trustedProxyIps);
        return countersFor(today)
            .flatMap(daily -> recordEvent(today, daily, clientIp, userAgent))
            .onErrorResume(error -> {
                log.warn("Failed to record Palette Nav visit: {}", error.toString());
                return Mono.<Void>empty();
            });
    }

    /**
     * Opens the periodic flush loop. Called after the plugin context is ready so the
     * extension client can be used.
     */
    public synchronized void start() {
        if (flushSubscription != null && !flushSubscription.isDisposed()) {
            return;
        }
        flushSubscription = Flux.interval(FLUSH_INTERVAL)
            .concatMap(tick -> flush()
                .onErrorResume(error -> {
                    log.warn("Failed to flush Palette Nav visit counters", error);
                    return Mono.empty();
                }))
            .subscribe(null,
                error -> log.warn("Palette Nav visit flush loop stopped", error));
    }

    /**
     * Stops the loop and writes whatever is still buffered. The final write is bounded
     * so plugin shutdown cannot hang on the extension store.
     */
    public synchronized void stop() {
        var current = flushSubscription;
        flushSubscription = null;
        if (current != null) {
            current.dispose();
        }
        try {
            flush().block(FLUSH_TIMEOUT);
        } catch (RuntimeException error) {
            log.warn("Failed to flush Palette Nav visit counters on shutdown: {}",
                error.toString());
        }
    }

    /**
     * Writes all buffered deltas and removes expired visitor records. Exposed for the
     * flush loop and the shutdown path; overlapping calls only ever exchange a delta
     * once because each pending counter is swapped atomically.
     */
    public Mono<Void> flush() {
        var today = LocalDate.now();
        return Flux.fromIterable(counters.entrySet())
            .concatMap(entry -> flushCounters(entry.getKey(), entry.getValue()))
            .then(Mono.defer(() -> cleanupExpiredVisitors(today)));
    }

    /**
     * Returns dashboard counters and the last seven days (today included).
     */
    public Mono<NavDashboardStats> dashboardStats() {
        var today = LocalDate.now();
        return Mono.zip(
                client.list(NavVisitStat.class, NavEntitySupport::alive,
                        Comparator.comparing(NavStatsService::dateOf))
                    .collectList(),
                // countBy reads the extension index; the list() overload used to
                // materialise and sort the whole link table for a single count.
                client.countBy(NavLink.class, ListOptions.builder().build())
            )
            .map(tuple -> buildStats(today, tuple.getT1(), tuple.getT2()));
    }

    /**
     * Returns the counters shown in the public page footer. Reading statistics is a
     * best-effort feature: a store failure renders zeros instead of failing the page.
     * Buffered deltas are not included, matching the dashboard (they are flushed
     * within {@link #FLUSH_INTERVAL}).
     */
    public Mono<NavFooterStats> footerStats() {
        var today = LocalDate.now();
        var snapshot = footerSnapshot;
        if (snapshot != null && snapshot.date().equals(today)
            && System.currentTimeMillis() - snapshot.readAtMillis()
                < FOOTER_CACHE_TTL.toMillis()) {
            return Mono.just(snapshot.stats());
        }
        return client.list(NavVisitStat.class, NavEntitySupport::alive,
                Comparator.comparing(NavStatsService::dateOf))
            .collectList()
            .map(stats -> buildFooterStats(today, stats))
            // Only successful reads replace the cache, so a transient store failure
            // is retried on the next render instead of sticking for the whole TTL.
            .doOnNext(stats -> footerSnapshot =
                new FooterSnapshot(stats, today, System.currentTimeMillis()))
            .onErrorResume(error -> {
                log.warn("Failed to read Palette Nav visit statistics: {}", error.toString());
                return Mono.just(new NavFooterStats(0L, 0L, 0L));
            });
    }

    /**
     * Counts one page view. PV is counted even when the visitor bookkeeping fails,
     * because the page was rendered; UV only moves when this visitor created its
     * resource for the day.
     */
    private Mono<Void> recordEvent(LocalDate date, DailyCounters daily, String clientIp,
        String userAgent) {
        var spec = daily.salt;
        if (!StringUtils.hasText(spec)) {
            // ensureDailyStat always persists a salt; this only guards against
            // a concurrent writer replacing the spec with an empty one.
            log.warn("Daily visit stat {} has no salt; skipping this visit", date);
            return Mono.empty();
        }
        daily.pendingPv.incrementAndGet();
        if (daily.observedUv.get() >= MAX_VISITORS_PER_DAY) {
            warnDailyLimit(date);
            // The visitor set is capped, so the UV counter would not move even
            // for a genuinely new visitor. The page view above is still counted.
            return Mono.empty();
        }
        var hash = visitorHash(spec, clientIp, userAgent);
        return rememberVisitor(date, hash)
            .doOnNext(unique -> {
                if (unique) {
                    daily.observedUv.incrementAndGet();
                    daily.pendingUv.incrementAndGet();
                }
            })
            .onErrorResume(error -> {
                log.warn("Failed to record a Palette Nav visitor: {}", error.toString());
                return Mono.just(false);
            })
            .then();
    }

    /**
     * Returns the cached counters for the date, creating them (with a fresh random
     * visitor salt) on the first visit of the day. The salt is cached so the daily
     * stat is not read on every request.
     */
    private Mono<DailyCounters> countersFor(LocalDate date) {
        var cached = counters.get(date);
        if (cached != null) {
            return Mono.just(cached);
        }
        return ensureDailyStat(date).map(stat -> {
            var spec = stat.getSpec();
            var created = new DailyCounters(
                spec == null ? "" : spec.getSalt(),
                spec == null ? 0L : valueOf(spec.getUv()));
            var existing = counters.putIfAbsent(date, created);
            return existing == null ? created : existing;
        });
    }

    /**
     * Swaps out the pending deltas for one date and writes them. A failed write puts
     * them back so the next flush retries instead of silently losing a batch.
     */
    Mono<Void> flushCounters(LocalDate date, DailyCounters daily) {
        var pv = daily.pendingPv.getAndSet(0);
        var uv = daily.pendingUv.getAndSet(0);
        if (pv == 0 && uv == 0) {
            removeIfNotToday(date, daily);
            return Mono.empty();
        }
        // Settle exactly once. A completed write must not be restored; an error or a
        // cancellation must put the swapped deltas back. Cancellation happens when
        // stop() disposes the flush loop while a write is in flight: without this the
        // final flush would see zero pending counters and the batch would be lost.
        var settled = new AtomicBoolean();
        // Remove a past date only after a successful write. Putting the deltas back
        // in onErrorResume and then removing the entry in doOnSuccess would drop them:
        // onErrorResume completes the sequence normally, so doOnSuccess still runs.
        return bumpDailyStat(date, pv, uv)
            .doOnSuccess(ignored -> {
                settled.set(true);
                removeIfNotToday(date, daily);
            })
            .onErrorResume(error -> {
                if (settled.compareAndSet(false, true)) {
                    restore(daily, pv, uv);
                }
                log.warn("Failed to flush Palette Nav counters for {}: {}", date,
                    error.toString());
                return Mono.empty();
            })
            .doFinally(signal -> {
                if (signal == SignalType.CANCEL && settled.compareAndSet(false, true)) {
                    restore(daily, pv, uv);
                }
            })
            .then();
    }

    private static void restore(DailyCounters daily, long pv, long uv) {
        daily.pendingPv.addAndGet(pv);
        daily.pendingUv.addAndGet(uv);
    }

    private void removeIfNotToday(LocalDate date, DailyCounters daily) {
        if (!date.equals(LocalDate.now())) {
            counters.remove(date, daily);
        }
    }

    /**
     * Returns the daily counter, creating it (with a fresh random visitor salt) on the
     * first visit of the day. A concurrent creator wins the insert; the loser retries
     * the fetch and reuses the winner's salt.
     */
    private Mono<NavVisitStat> ensureDailyStat(LocalDate date) {
        var name = STAT_NAME_PREFIX + date;
        return retryConflict(Mono.defer(() ->
            client.fetch(NavVisitStat.class, name)
                .flatMap(stat -> {
                    var spec = stat.getSpec();
                    var changed = false;
                    if (spec == null) {
                        spec = new NavVisitStat.Spec();
                        stat.setSpec(spec);
                        changed = true;
                    }
                    if (!StringUtils.hasText(spec.getDate())) {
                        spec.setDate(date.toString());
                        changed = true;
                    }
                    if (!StringUtils.hasText(spec.getSalt())) {
                        spec.setSalt(newSalt());
                        changed = true;
                    }
                    return changed ? client.update(stat) : Mono.just(stat);
                })
                .switchIfEmpty(Mono.defer(() -> client.create(createStat(name, date))))
        ));
    }

    /**
     * Creates the visitor resource. A duplicate name means the visitor was already
     * counted today; that is the atomic deduplication primitive and is not retried.
     */
    private Mono<Boolean> rememberVisitor(LocalDate date, String hash) {
        var visitor = new NavVisitVisitor();
        var metadata = new Metadata();
        metadata.setName(visitorName(date, hash));
        metadata.setLabels(Map.of(VISITOR_DATE_LABEL, date.toString()));
        visitor.setMetadata(metadata);

        var spec = new NavVisitVisitor.Spec();
        spec.setDate(date.toString());
        spec.setHash(hash);
        visitor.setSpec(spec);
        return client.create(visitor)
            .map(created -> true)
            .onErrorResume(NavStatsService::isDuplicateName, error -> Mono.just(false));
    }

    /**
     * Adds the buffered deltas to the daily counter. Creation uses the salt cached for
     * the same date so visitor hashes from earlier in the day keep deduplicating even
     * if the counter resource was removed mid-day.
     */
    private Mono<Void> bumpDailyStat(LocalDate date, long pvDelta, long uvDelta) {
        var name = STAT_NAME_PREFIX + date;
        return retryConflict(Mono.defer(() ->
            client.fetch(NavVisitStat.class, name)
                .flatMap(stat -> {
                    var spec = stat.getSpec();
                    if (spec == null) {
                        spec = new NavVisitStat.Spec();
                        stat.setSpec(spec);
                    }
                    spec.setDate(date.toString());
                    spec.setPv(valueOf(spec.getPv()) + pvDelta);
                    spec.setUv(valueOf(spec.getUv()) + uvDelta);
                    return client.update(stat);
                })
                .switchIfEmpty(Mono.defer(() ->
                    client.create(createStat(name, date, pvDelta, uvDelta))))
                .then()
        ));
    }

    private NavVisitStat createStat(String name, LocalDate date) {
        var stat = new NavVisitStat();
        var metadata = new Metadata();
        metadata.setName(name);
        stat.setMetadata(metadata);

        var spec = new NavVisitStat.Spec();
        spec.setDate(date.toString());
        spec.setSalt(newSalt());
        spec.setPv(0L);
        spec.setUv(0L);
        stat.setSpec(spec);
        return stat;
    }

    private NavVisitStat createStat(String name, LocalDate date, long pv, long uv) {
        var stat = createStat(name, date);
        var spec = stat.getSpec();
        var cached = counters.get(date);
        spec.setSalt(cached == null ? newSalt() : cached.salt);
        spec.setPv(pv);
        spec.setUv(uv);
        return stat;
    }

    /**
     * Deletes visitor resources that are no longer needed for same-day deduplication.
     * The per-day label index selects the expired records, so the cleanup does not load
     * every visitor. Runs at most once per day per instance; a failure only releases the
     * guard so the next flush retries, and it never surfaces to the page.
     */
    private Mono<Void> cleanupExpiredVisitors(LocalDate today) {
        // Deferred so the once-per-day guard only advances when this Mono is actually
        // subscribed. If a flush fails before reaching this step, the next flush must
        // still run the cleanup instead of skipping it outright.
        return Mono.defer(() -> {
            var previous = lastVisitorCleanup.getAndSet(today);
            if (today.equals(previous)) {
                return Mono.<Void>empty();
            }
            var cutoff = today.minusDays(VISITOR_RETENTION_DAYS);
            var options = ListOptions.builder()
                .labelSelector()
                .exists(VISITOR_DATE_LABEL)
                .notIn(VISITOR_DATE_LABEL, today.toString(), cutoff.toString())
                .end()
                .build();
            return client.listAll(NavVisitVisitor.class, options, Sort.unsorted())
                .flatMap(client::delete, CLEANUP_CONCURRENCY)
                .then()
                // A failed or cancelled cleanup must not fail the flush it was attached
                // to, and the guard is released so a later flush can retry.
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        lastVisitorCleanup.compareAndSet(today, null);
                    }
                })
                .onErrorResume(error -> {
                    lastVisitorCleanup.compareAndSet(today, null);
                    log.warn("Failed to clean up expired Palette Nav visitor records: {}",
                        error.toString());
                    return Mono.empty();
                });
        });
    }

    private void warnDailyLimit(LocalDate date) {
        var previous = lastLimitWarning.getAndSet(date);
        if (!date.equals(previous)) {
            log.warn("Palette Nav reached the daily distinct visitor limit of {}; "
                + "visits beyond it still count as PV but no longer as UV", date,
                MAX_VISITORS_PER_DAY);
        }
    }

    private <T> Mono<T> retryConflict(Mono<T> action) {
        return action.retryWhen(Retry.backoff(MAX_CONFLICT_RETRIES, Duration.ofMillis(50))
            .maxBackoff(Duration.ofMillis(800))
            .filter(NavStatsService::isRetryableConflict));
    }

    private static boolean isRetryableConflict(Throwable error) {
        return error instanceof OptimisticLockingFailureException
            || isDuplicateName(error);
    }

    private static boolean isDuplicateName(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            if (DUPLICATE_NAME_EXCEPTION.equals(current.getClass().getName())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * Resolves the client address without trusting headers from arbitrary peers.
     *
     * <p>{@code X-Forwarded-For} is only read when the TCP peer is a loopback address
     * (a proxy on the same host) or is listed in the {@code trustedProxyIps} setting.
     * A private peer is deliberately <em>not</em> trusted by default: on an intranet
     * deployment every visitor connects from RFC 1918 space, so trusting the whole
     * range would let any visitor forge a fresh identity per request and inflate UV.
     * For trusted proxies the rightmost entry is used: with {@code
     * $proxy_add_x_forwarded_for} every hop appends, so the rightmost value is the peer
     * address the trusted proxy actually saw, while the leftmost value is client-supplied
     * and trivial to forge.</p>
     */
    static String resolveClientIp(String forwardedFor, String realIp, String remoteAddress,
        String trustedProxyIps) {
        var remote = trimToNull(remoteAddress);
        if (remote != null) {
            if (!isTrustedProxy(remote, trustedProxyIps)) {
                return clip(remote);
            }
            var forwarded = lastForwardedAddress(forwardedFor);
            if (forwarded != null) {
                return forwarded;
            }
            var real = trimToNull(realIp);
            return real == null ? clip(remote) : clip(real);
        }
        var forwarded = lastForwardedAddress(forwardedFor);
        if (forwarded != null) {
            return forwarded;
        }
        var real = trimToNull(realIp);
        return real == null ? "unknown" : clip(real);
    }

    private static String lastForwardedAddress(String forwardedFor) {
        if (!StringUtils.hasText(forwardedFor)) {
            return null;
        }
        var parts = forwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            var candidate = parts[i].trim();
            if (!candidate.isEmpty()) {
                return clip(candidate);
            }
        }
        return null;
    }

    private static boolean isTrustedProxy(String host, String trustedProxyIps) {
        if (!IP_LITERAL.matcher(host).matches()) {
            // A host name would require DNS resolution here; the socket peer is
            // always numeric, so refusing names also avoids attacker-driven lookups.
            return false;
        }
        InetAddress peer;
        try {
            peer = InetAddress.getByName(host);
        } catch (UnknownHostException error) {
            return false;
        }
        // A same-host proxy connects over loopback, which a remote client cannot fake.
        if (peer.isLoopbackAddress()) {
            return true;
        }
        if (!StringUtils.hasText(trustedProxyIps)) {
            return false;
        }
        for (var raw : trustedProxyIps.split(",")) {
            var candidate = raw.trim();
            if (candidate.isEmpty() || !IP_LITERAL.matcher(candidate).matches()) {
                continue;
            }
            try {
                if (InetAddress.getByName(candidate).equals(peer)) {
                    return true;
                }
            } catch (UnknownHostException ignored) {
                // Not a valid literal; the settings normalizer keeps it out of the way.
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clip(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100);
    }

    static String visitorHash(String salt, String clientIp, String userAgent) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var input = (salt == null ? "" : salt) + "|"
                + (clientIp == null ? "" : clientIp) + "|"
                + (userAgent == null ? "" : userAgent);
            return HexFormat.of()
                .formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)))
                .substring(0, VISITOR_HASH_LENGTH);
        } catch (NoSuchAlgorithmException error) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static String newSalt() {
        var bytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String visitorName(LocalDate date, String hash) {
        return VISITOR_NAME_PREFIX + date + "-" + hash;
    }

    static NavDashboardStats buildStats(LocalDate today, List<NavVisitStat> stats,
        long linkCount) {
        var byDate = new HashMap<String, NavVisitStat>();
        long totalPv = 0L;
        for (var stat : stats) {
            var spec = stat.getSpec();
            if (spec == null || !StringUtils.hasText(spec.getDate())) {
                continue;
            }
            byDate.put(spec.getDate(), stat);
            totalPv += valueOf(spec.getPv());
        }

        var daily = new ArrayList<NavDailyVisit>(7);
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            var date = today.minusDays(daysAgo).toString();
            var spec = byDate.containsKey(date) ? byDate.get(date).getSpec() : null;
            daily.add(new NavDailyVisit(date,
                spec == null ? 0L : valueOf(spec.getPv()),
                spec == null ? 0L : valueOf(spec.getUv())));
        }

        var todaySpec = byDate.containsKey(today.toString())
            ? byDate.get(today.toString()).getSpec() : null;
        return new NavDashboardStats(linkCount,
            todaySpec == null ? 0L : valueOf(todaySpec.getPv()),
            todaySpec == null ? 0L : valueOf(todaySpec.getUv()),
            totalPv, daily);
    }

    /**
     * Sums the all-time PV and picks today's counters. Delegates to {@link #buildStats}
     * so the dashboard and footer always agree on skipped specs, duplicated dates
     * (last wins) and the accumulated total.
     */
    static NavFooterStats buildFooterStats(LocalDate today, List<NavVisitStat> daily) {
        var stats = buildStats(today, daily, 0L);
        return new NavFooterStats(stats.todayPv(), stats.todayUv(), stats.totalPv());
    }

    private static long valueOf(Long value) {
        return value == null ? 0L : value;
    }

    private static String dateOf(NavVisitStat stat) {
        var spec = stat.getSpec();
        return spec == null || spec.getDate() == null ? "" : spec.getDate();
    }

    /** Footer aggregate plus the date and time it was read at. */
    private record FooterSnapshot(NavFooterStats stats, LocalDate date, long readAtMillis) {
    }

    /** Salt for the day plus the counters waiting to be written. */
    static final class DailyCounters {
        final String salt;
        /** Total distinct visitors observed today, used for the daily UV cap. */
        final AtomicLong observedUv;
        final AtomicLong pendingPv = new AtomicLong();
        final AtomicLong pendingUv = new AtomicLong();

        DailyCounters(String salt, long uv) {
            this.salt = salt;
            this.observedUv = new AtomicLong(uv);
        }
    }
}