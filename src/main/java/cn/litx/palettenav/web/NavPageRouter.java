package cn.litx.palettenav.web;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.service.NavPublicViewService;
import cn.litx.palettenav.service.NavStatsService;
import cn.litx.palettenav.service.NavVisitRecorder;
import cn.litx.palettenav.service.NavYiyanService;
import java.util.HashMap;
import java.util.Map;
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
 *
 * <p>All display values come from {@link NavPublicViewService}, the same source the
 * public JSON endpoint uses, so both representations stay in sync.</p>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class NavPageRouter {

    private final NavPublicViewService publicViewService;
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

        return publicViewService.load(userAgent, contextPath)
            .flatMap(loaded -> {
                var general = loaded.general();
                var model = toModel(loaded.view(), general, contextPath);

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

    /** Maps the public view onto the flat model keys consumed by the template. */
    Map<String, Object> toModel(NavPublicView view, NavSettings.General general,
        String contextPath) {
        var site = view.getSite();
        var appearance = view.getAppearance();
        var search = view.getSearch();
        var footer = view.getFooter();

        var model = new HashMap<String, Object>();
        model.put(ModelConst.TEMPLATE_ID, NavConstants.TEMPLATE_ID);
        model.put("categories", view.getCategories());
        model.put("searchEngines", view.getSearchEngines());
        model.put("quickLinks", view.getQuickLinks());
        model.put("iconSpriteHtml", view.getSpriteHtml());
        model.put("contextPath", contextPath);
        model.put("pluginVersion", pluginContext.getVersion());
        model.put("defaultColor", appearance.getDefaultColor());
        model.put("defaultNightMode", appearance.getDefaultNightMode());
        model.put("showClock", appearance.isShowClock());
        model.put("showYiyan", appearance.isShowYiyan());
        model.put("showBackgroundByDefault", appearance.isShowBackgroundByDefault());
        model.put("showCategoryIconsInNav", appearance.isShowCategoryIconsInNav());
        model.put("yanText", appearance.isShowYiyan()
            ? yiyanService.randomQuote() : "");
        model.put("backgroundUrl", appearance.getBackgroundUrl());
        // The template prints the video URL without Thymeleaf's @{} expression.
        model.put("backgroundVideoUrl",
            NavPublicViewService.contextRelative(appearance.getBackgroundVideoUrl(),
                contextPath));
        model.put("baiduSuggestions", search.isEnableBaiduSuggestions());
        model.put("logoUrl", site.getLogoUrl());
        model.put("brandText", site.getBrandText());
        model.put("copyright", footer.getCopyright());
        model.put("icp", footer.getIcp());
        model.put("gonganbei", footer.getGonganbei());
        model.put("gonganbeiCode", footer.getGonganbeiCode());
        model.put("icpLogoUrl", footer.getIcpLogoUrl());
        model.put("gonganbeiLogoUrl", footer.getGonganbeiLogoUrl());
        model.put("seoDescription", site.getSiteDescription());
        model.put("seoKeywords", site.getSiteKeywords());
        model.put("siteName", site.getSiteName());
        model.put("showFooterVisitStats",
            Boolean.TRUE.equals(general.getShowFooterVisitStats()));
        return model;
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

    private String remoteAddress(ServerRequest request) {
        var address = request.exchange().getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "";
        }
        return address.getAddress().getHostAddress();
    }
}