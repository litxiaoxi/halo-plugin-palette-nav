package cn.litx.palettenav.service;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.config.NavSettingsService;
import cn.litx.palettenav.config.NavSiteInfoService;
import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.web.NavPageData;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Single source for the values the public navigation page exposes.
 *
 * <p>Both {@code /nav} and the public JSON endpoint go through this service, so the
 * rendered HTML and the JSON can never drift. The only page-only value left in the
 * router is the randomly chosen yiyan quote, which is not data worth serving.</p>
 */
@Service
@RequiredArgsConstructor
public class NavPublicViewService {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final NavSettingsService settingsService;
    private final NavSiteInfoService siteInfoService;
    private final NavQueryService queryService;
    private final NavIconSpriteService iconSpriteService;
    private final NavFooterSanitizer footerSanitizer;

    public Mono<LoadedNavView> load(String userAgent, String contextPath) {
        return Mono.zip(
                settingsService.get(),
                siteInfoService.get(),
                queryService.loadVisiblePageData(userAgent, contextPath)
            )
            .map(tuple -> new LoadedNavView(
                build(tuple.getT1(), tuple.getT2(), tuple.getT3(), contextPath),
                tuple.getT1().getGeneral()));
    }

    NavPublicView build(NavSettings.Snapshot settings, NavSiteInfoService.SiteInfo siteInfo,
        NavPageData data, String contextPath) {
        var general = settings.getGeneral();
        var appearance = settings.getAppearance();
        var search = settings.getSearch();

        var configuredBackground = NavUrlValidator.trim(appearance.getBackgroundUrl());
        var videoBackground = NavMediaType.VIDEO.equals(appearance.getBackgroundType())
            && NavUrlValidator.isSafeImageUrl(configuredBackground);

        return NavPublicView.builder()
            .categories(data.getCategories().stream().map(this::toCategoryView).toList())
            .searchEngines(data.getSearchEngines().stream()
                .map(this::toSearchEngineView).toList())
            .quickLinks(data.getQuickLinks().stream().map(this::toQuickLinkView).toList())
            .spriteHtml(iconSpriteService.render(data.getSpriteIds()))
            .contextPath(contextPath)
            .site(NavPublicView.SiteView.builder()
                .siteName(NavTextUtils.firstText(general.getSiteName(), siteInfo.title()))
                .brandText(resolveBrandText(general.getBrandText(),
                    general.getSiteName(), siteInfo.title()))
                .logoUrl(resolveLogo(general.getLogoUrl()))
                .siteDescription(NavTextUtils.firstText(general.getSiteDescription(),
                    siteInfo.subtitle()))
                .siteKeywords(general.getSiteKeywords())
                .build())
            .appearance(NavPublicView.AppearanceView.builder()
                .defaultColor(appearance.getDefaultColor())
                .defaultNightMode(appearance.getDefaultNightMode())
                .showClock(Boolean.TRUE.equals(appearance.getShowClock()))
                .showYiyan(Boolean.TRUE.equals(appearance.getShowYiyan()))
                .showBackgroundByDefault(Boolean.TRUE.equals(
                    appearance.getShowBackgroundByDefault()))
                .showCategoryIconsInNav(Boolean.TRUE.equals(
                    appearance.getShowCategoryIconsInNav()))
                .backgroundType(videoBackground
                    ? NavMediaType.VIDEO
                    : NavMediaType.IMAGE)
                .backgroundUrl(videoBackground
                    ? NavConstants.DEFAULT_BACKGROUND_ASSET
                    : effectiveBackground(configuredBackground))
                // Root-relative, like every other URL in the payload; {@code /nav}
                // adds the context path itself before rendering.
                .backgroundVideoUrl(videoBackground ? configuredBackground : "")
                .build())
            .search(NavPublicView.SearchView.builder()
                .enableBaiduSuggestions(Boolean.TRUE.equals(
                    search.getEnableBaiduSuggestions()))
                .build())
            .footer(NavPublicView.FooterView.builder()
                .copyright(footerSanitizer.sanitize(general.getCopyright()))
                .icp(general.getIcp())
                .gonganbei(general.getGonganbei())
                .gonganbeiCode(firstDigits(general.getGonganbei()))
                .icpLogoUrl(resolveOptionalImage(general.getIcpLogoUrl()))
                .gonganbeiLogoUrl(resolveOptionalImage(general.getGonganbeiLogoUrl()))
                .build())
            .build();
    }

    private String resolveLogo(String configured) {
        if (NavUrlValidator.isSafeImageUrl(configured)) {
            return configured.trim();
        }
        return NavConstants.DEFAULT_LOGO_ASSET;
    }

    /**
     * Optional images (the ICP / public security record icons) render only when the
     * configured URL passes the same allow-list as other media; there is no fallback.
     */
    private String resolveOptionalImage(String configured) {
        return NavUrlValidator.isSafeImageUrl(configured) ? configured.trim() : "";
    }

    private String resolveBrandText(String configured, String siteName, String siteTitle) {
        var direct = NavUrlValidator.trim(configured);
        if (!direct.isEmpty()) {
            return direct;
        }
        var title = NavUrlValidator.trim(siteName);
        if (title.isEmpty()) {
            title = NavUrlValidator.trim(siteTitle);
        }
        if (title.isEmpty()) {
            return "";
        }
        var dash = title.indexOf('-');
        if (dash > 0) {
            var prefix = title.substring(0, dash).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        return title;
    }

    /**
     * The video data attribute is rendered without Thymeleaf's {@code @{...}}
     * link expression, so site-relative uploads need the context path here. The
     * public JSON keeps URLs root-relative and exposes {@code contextPath} instead.
     */
    public static String contextRelative(String url, String contextPath) {
        if (url.startsWith("/") && !url.startsWith("//") && !contextPath.isEmpty()) {
            return contextPath + url;
        }
        return url;
    }

    private String effectiveBackground(String configured) {
        return NavUrlValidator.isSafeImageUrl(configured)
            ? configured.trim()
            : NavConstants.DEFAULT_BACKGROUND_ASSET;
    }

    private String firstDigits(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var matcher = DIGITS.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private NavPublicView.CategoryView toCategoryView(NavPageData.CategoryView view) {
        return NavPublicView.CategoryView.builder()
            .name(view.getName())
            .displayName(view.getDisplayName())
            .iconHtml(view.getIconHtml())
            .links(view.getLinks().stream().map(this::toLinkView).toList())
            .build();
    }

    private NavPublicView.LinkView toLinkView(NavPageData.LinkView view) {
        return NavPublicView.LinkView.builder()
            .name(view.getName())
            .displayName(view.getDisplayName())
            .url(view.getUrl())
            .iconHtml(view.getIconHtml())
            .build();
    }

    private NavPublicView.SearchEngineView toSearchEngineView(
        NavPageData.SearchEngineView view) {
        return NavPublicView.SearchEngineView.builder()
            .alias(view.getAlias())
            .displayName(view.getDisplayName())
            .hint(view.getHint())
            .iconHtml(view.getIconHtml())
            .searchUrl(view.getSearchUrl())
            .mobileSearchUrl(view.getMobileSearchUrl())
            .build();
    }

    private NavPublicView.QuickLinkView toQuickLinkView(NavPageData.QuickLinkView view) {
        return NavPublicView.QuickLinkView.builder()
            .displayName(view.getDisplayName())
            .url(view.getUrl())
            .openInNewTab(view.isOpenInNewTab())
            .build();
    }

    /** View plus the settings group the {@code /nav} router still needs for PV recording. */
    public record LoadedNavView(NavPublicView view, NavSettings.General general) {
    }
}