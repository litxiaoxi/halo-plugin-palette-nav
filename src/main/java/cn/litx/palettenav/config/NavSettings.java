package cn.litx.palettenav.config;

import cn.litx.palettenav.service.NavMediaType;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * Settings schema for the plugin. Each nested class maps to one config group in the
 * plugin's ConfigMap; the plugin does not declare a Halo Setting form.
 */
public final class NavSettings {

    /**
     * Business settings live in this ConfigMap, deliberately not declared as
     * {@code spec.configMapName} in plugin.yaml: Halo deletes that ConfigMap on
     * "uninstall and delete config", but navigation settings must survive.
     */
    public static final String CONFIG_MAP_NAME = "palette-nav-config";

    public static final String GROUP_GENERAL = "general";
    public static final String GROUP_APPEARANCE = "appearance";
    public static final String GROUP_SEARCH = "search";
    public static final String GROUP_ICONS = "icons";

    public static final Set<String> COLORS =
        Set.of("purple", "blue", "green", "pink", "orange", "teal");
    public static final Set<String> NIGHT_MODES = Set.of("auto", "light", "dark");

    private NavSettings() {
    }

    @Data
    public static class General {
        private String siteName;
        private String siteDescription;
        private String siteKeywords;
        private String logoUrl;
        private String brandText;
        private String copyright;
        private String icp;
        private String gonganbei;
        /** Optional icon shown before the ICP record number; empty renders text only. */
        private String icpLogoUrl;
        /** Optional icon shown before the public security record number. */
        private String gonganbeiLogoUrl;
        /** Whether the public /nav page should record visit statistics. */
        private Boolean enableVisitStats;
        /**
         * Whether the public page footer shows today's page views / visitors and the
         * accumulated page views above the record numbers. Independent of
         * {@link #enableVisitStats}: stopping the recording only freezes the numbers.
         */
        private Boolean showFooterVisitStats;
        /**
         * Comma-separated proxy addresses whose {@code X-Forwarded-For} /
         * {@code X-Real-IP} headers are trusted. Loopback peers are always trusted
         * (a proxy on the same host), so an empty value means "same-host proxy only".
         */
        private String trustedProxyIps;

        private static final String DEFAULT_SITE_NAME = "上网导航";
        private static final String DEFAULT_SITE_DESCRIPTION =
            "为 Halo 打造的上网导航页：分类、搜索、快捷入口、配色与背景随心换，不依赖主题，独立运行。";
        private static final String DEFAULT_SITE_KEYWORDS =
            "Palette Nav导航,百度搜索,哔哩哔哩搜索,知乎搜索,Palette Nav,网站导航,上网导航";
        private static final String DEFAULT_BRAND_TEXT = "Palette Nav";

        /** Defaults applied when an installation has no general setting group yet. */
        public static General defaults() {
            var general = new General();
            general.setSiteName(DEFAULT_SITE_NAME);
            general.setSiteDescription(DEFAULT_SITE_DESCRIPTION);
            general.setSiteKeywords(DEFAULT_SITE_KEYWORDS);
            general.setBrandText(DEFAULT_BRAND_TEXT);
            return general.normalized();
        }

        public General normalized() {
            // A missing field gets the product default; an explicit empty string is
            // user intent and stays empty, matching ui/src/api/settings.ts.
            siteName = trimToDefault(siteName, DEFAULT_SITE_NAME);
            siteDescription = trimToDefault(siteDescription, DEFAULT_SITE_DESCRIPTION);
            siteKeywords = trimToDefault(siteKeywords, DEFAULT_SITE_KEYWORDS);
            logoUrl = trim(logoUrl);
            brandText = trimToDefault(brandText, DEFAULT_BRAND_TEXT);
            copyright = trim(copyright);
            icp = trim(icp);
            gonganbei = trim(gonganbei);
            icpLogoUrl = trim(icpLogoUrl);
            gonganbeiLogoUrl = trim(gonganbeiLogoUrl);
            enableVisitStats = enableVisitStats == null || enableVisitStats;
            showFooterVisitStats = showFooterVisitStats != null && showFooterVisitStats;
            trustedProxyIps = normalizeList(trustedProxyIps);
            return this;
        }
    }

    @Data
    public static class Appearance {
        private String defaultColor;
        private String defaultNightMode;
        private Boolean showClock;
        private Boolean showYiyan;
        /**
         * Whether first-time visitors see the configured background immediately.
         * A visitor's own toggle choice (localStorage) always wins afterwards.
         */
        private Boolean showBackgroundByDefault;
        /** Whether the in-page category nav shows each category's icon. */
        private Boolean showCategoryIconsInNav;
        /** Merged background media URL; the kind is stored in {@link #backgroundType}. */
        private String backgroundUrl;
        private String backgroundType;
        /**
         * Legacy split settings. Kept so configs written before the merge still
         * render until the first save rewrites the appearance group.
         */
        @Deprecated
        private String backgroundImage;
        @Deprecated
        private String backgroundVideo;

        /** Defaults applied when an installation has no appearance setting group yet. */
        public static Appearance defaults() {
            var appearance = new Appearance();
            appearance.setDefaultColor("purple");
            appearance.setDefaultNightMode("auto");
            appearance.setShowClock(true);
            appearance.setShowYiyan(true);
            return appearance.normalized();
        }

        public Appearance normalized() {
            var color = trim(defaultColor).toLowerCase(Locale.ROOT);
            defaultColor = COLORS.contains(color) ? color : "purple";

            var night = trim(defaultNightMode).toLowerCase(Locale.ROOT);
            defaultNightMode = NIGHT_MODES.contains(night) ? night : "auto";

            showClock = showClock == null || showClock;
            showYiyan = showYiyan == null || showYiyan;
            showBackgroundByDefault = showBackgroundByDefault != null && showBackgroundByDefault;
            showCategoryIconsInNav = showCategoryIconsInNav != null && showCategoryIconsInNav;

            backgroundUrl = trim(backgroundUrl);
            var type = trim(backgroundType);
            if (backgroundUrl.isEmpty()) {
                var legacyVideo = trim(backgroundVideo);
                var legacyImage = trim(backgroundImage);
                if (!legacyVideo.isEmpty()) {
                    backgroundUrl = legacyVideo;
                    if (!NavMediaType.isValid(type)) {
                        type = NavMediaType.VIDEO;
                    }
                } else if (!legacyImage.isEmpty()) {
                    backgroundUrl = legacyImage;
                    if (!NavMediaType.isValid(type)) {
                        type = NavMediaType.IMAGE;
                    }
                }
            }
            backgroundType = backgroundUrl.isEmpty()
                ? NavMediaType.IMAGE
                : NavMediaType.normalize(type, backgroundUrl);
            return this;
        }
    }

    @Data
    public static class Search {
        private Boolean enableBaiduSuggestions;

        /** Defaults applied when an installation has no search setting group yet. */
        public static Search defaults() {
            var search = new Search();
            search.setEnableBaiduSuggestions(true);
            return search.normalized();
        }

        public Search normalized() {
            enableBaiduSuggestions = enableBaiduSuggestions == null || enableBaiduSuggestions;
            return this;
        }
    }

    /**
     * Where icons fetched from a link target are stored.
     *
     * <p>An empty {@code policyName} means "use the highest priority storage policy",
     * matching Halo's own upload UI; an empty {@code groupName} means "no group".</p>
     */
    @Data
    public static class Icons {
        private String policyName;
        private String groupName;

        /** Defaults applied when an installation has no icons setting group yet. */
        public static Icons defaults() {
            return new Icons().normalized();
        }

        public Icons normalized() {
            policyName = trim(policyName);
            groupName = trim(groupName);
            return this;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Snapshot {
        private General general;
        private Appearance appearance;
        private Search search;
        private Icons icons;

        public static Snapshot defaults() {
            return new Snapshot(General.defaults(), Appearance.defaults(),
                Search.defaults(), Icons.defaults());
        }
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String trimToDefault(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    /** Normalizes a comma-separated address list: trimmed, de-duplicated, no blanks. */
    private static String normalizeList(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        var entries = new LinkedHashSet<String>();
        for (var item : value.split(",")) {
            var trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return String.join(",", entries);
    }
}
