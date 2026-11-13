package cn.litx.palettenav.web;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.config.NavSettingsService;
import cn.litx.palettenav.config.NavSiteInfoService;
import cn.litx.palettenav.service.NavFooterSanitizer;
import cn.litx.palettenav.service.NavIconSpriteService;
import cn.litx.palettenav.service.NavMediaType;
import cn.litx.palettenav.service.NavQueryService;
import cn.litx.palettenav.service.NavStatsService;
import cn.litx.palettenav.service.NavUrlValidator;
import cn.litx.palettenav.service.NavVisitRecorder;
import cn.litx.palettenav.service.NavYiyanService;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.router.ModelConst;

/**
 * Public standalone page: {@code GET /nav}.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class NavPageRouter {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final NavQueryService queryService;
    private final NavIconSpriteService iconSpriteService;
    private final NavFooterSanitizer footerSanitizer;
    private final NavSettingsService settingsService;
    private final NavSiteInfoService siteInfoService;
    private final NavYiyanService yiyanService;
    private final NavVisitRecorder visitRecorder;
    private final NavStatsService statsService;
    private final PluginContext pluginContext;

    @Bean
    RouterFunction<ServerResponse> paletteNavPageRoute() {
        return route()
            .GET("/nav", this::render)
            .GET("/nav/", this::render)
            .build();
    }

    private Mono<ServerResponse> render(ServerRequest request) {
        var contextPath = request.exchange().getRequest().getPath().contextPath().value();
        var userAgent = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        request.exchange().getAttributes().put(ModelConst.NO_CACHE, true);

        return Mono.zip(
                settingsService.get(),
                siteInfoService.get(),
                queryService.loadVisiblePageData(userAgent, contextPath)
            )
            .flatMap(tuple -> {
                var settings = tuple.getT1();
                var siteInfo = tuple.getT2();
                var data = tuple.getT3();
                var general = settings.getGeneral();
                var appearance = settings.getAppearance();

                var model = new HashMap<String, Object>();
                model.put(ModelConst.TEMPLATE_ID, NavConstants.TEMPLATE_ID);
                model.put("categories", data.getCategories());
                model.put("searchEngines", data.getSearchEngines());
                model.put("quickLinks", data.getQuickLinks());
                model.put("iconSpriteHtml", iconSpriteService.render(data.getSpriteIds()));
                model.put("contextPath", contextPath);
                model.put("pluginVersion", pluginContext.getVersion());
                model.put("defaultColor", appearance.getDefaultColor());
                model.put("defaultNightMode", appearance.getDefaultNightMode());
                model.put("showClock", appearance.getShowClock());
                model.put("showYiyan", appearance.getShowYiyan());
                model.put("showBackgroundByDefault", appearance.getShowBackgroundByDefault());
                model.put("showCategoryIconsInNav", appearance.getShowCategoryIconsInNav());
                model.put("yanText", Boolean.TRUE.equals(appearance.getShowYiyan())
                    ? yiyanService.randomQuote() : "");
                var mergedBackground = NavUrlValidator.trim(appearance.getBackgroundUrl());
                var videoBackground = NavMediaType.VIDEO.equals(appearance.getBackgroundType())
                    && NavUrlValidator.isSafeImageUrl(mergedBackground);
                model.put("backgroundUrl", videoBackground
                    ? NavConstants.DEFAULT_BACKGROUND_ASSET
                    : effectiveBackground(mergedBackground));
                model.put("backgroundVideoUrl", videoBackground
                    ? contextRelative(mergedBackground, contextPath)
                    : "");
                model.put("baiduSuggestions", settings.getSearch().getEnableBaiduSuggestions());
                model.put("logoUrl", resolveLogo(general.getLogoUrl()));
                model.put("brandText", resolveBrandText(general.getBrandText(),
                    general.getSiteName(), siteInfo.title()));
                model.put("copyright", footerSanitizer.sanitize(general.getCopyright()));
                model.put("icp", general.getIcp());
                model.put("gonganbei", general.getGonganbei());
                model.put("gonganbeiCode", firstDigits(general.getGonganbei()));
                model.put("icpLogoUrl", resolveOptionalImage(general.getIcpLogoUrl()));
                model.put("gonganbeiLogoUrl",
                    resolveOptionalImage(general.getGonganbeiLogoUrl()));
                model.put("seoDescription", general.getSiteDescription());
                model.put("seoKeywords", general.getSiteKeywords());
                model.put("siteName", general.getSiteName());
                model.put("showFooterVisitStats",
                    Boolean.TRUE.equals(general.getShowFooterVisitStats()));

                if (!Boolean.TRUE.equals(general.getShowFooterVisitStats())) {
                    return respond(request, model, general, userAgent);
                }
                // The footer counters are rendered server-side; when the store is
                // unavailable footerStats() falls back to zeros rather than failing.
                return statsService.footerStats()
                    .map(stats -> {
                        model.put("footerTodayPv", stats.todayPv());
                        model.put("footerTodayUv", stats.todayUv());
                        model.put("footerTotalPv", stats.totalPv());
                        return model;
                    })
                    .flatMap(fullModel -> respond(request, fullModel, general, userAgent));
            });
    }

    private Mono<ServerResponse> respond(ServerRequest request, Map<String, Object> model,
        NavSettings.General general, String userAgent) {
        return ServerResponse.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "strict-origin-when-cross-origin")
            .render(NavConstants.TEMPLATE_ID, model)
            // Count only pages that actually rendered. The recorder queues the
            // visit; its single drain subscription runs off the request path,
            // so a slow stats update never delays or fails the response.
            .doOnSuccess(ignored -> {
                if (Boolean.FALSE.equals(general.getEnableVisitStats())) {
                    return;
                }
                visitRecorder.record(
                    request.headers().firstHeader("X-Forwarded-For"),
                    request.headers().firstHeader("X-Real-IP"),
                    remoteAddress(request),
                    userAgent,
                    general.getTrustedProxyIps());
            });
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
     * link expression, so site-relative uploads need the context path here.
     */
    private String contextRelative(String url, String contextPath) {
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

    private String remoteAddress(ServerRequest request) {
        var address = request.exchange().getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "";
        }
        return address.getAddress().getHostAddress();
    }
}
