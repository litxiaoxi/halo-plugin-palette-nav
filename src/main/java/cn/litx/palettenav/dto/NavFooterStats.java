package cn.litx.palettenav.dto;

/**
 * Counters rendered in the public page footer.
 *
 * <p>Kept separate from {@link NavDashboardStats} because the footer needs no link
 * count and no seven-day series; reading only the daily stat resources keeps the
 * page render from touching unrelated models.</p>
 */
public record NavFooterStats(long todayPv, long todayUv, long totalPv) {
}