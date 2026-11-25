package cn.litx.palettenav.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.service.NavPublicViewService;
import cn.litx.palettenav.service.NavQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * Public read-only API for themes and other pages: {@code GET /apis/api.palette-nav.halo.run/
 * v1alpha1/nav}.
 *
 * <p>Anonymous access is granted by the {@code palette-nav-role-public} role template,
 * which aggregates into both the {@code anonymous} and {@code authenticated} roles. The
 * endpoint never records page views; PV/UV only counts {@code /nav} HTML renders.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavPublicEndpoint implements CustomEndpoint {

    private static final String TAG = "NavPublicV1alpha1";

    /**
     * Themes may call this endpoint on every render. A short cache absorbs bursts
     * without making admin edits wait long; the key only varies by the two values
     * that change the payload.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final NavPublicViewService publicViewService;
    private final ConcurrentHashMap<CacheKey, CachedView> cache = new ConcurrentHashMap<>();

    private record CacheKey(boolean mobile, String contextPath) {
    }

    private record CachedView(NavPublicView view, Instant expiresAt) {
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/nav", this::nav, builder -> builder
                .operationId("FetchPaletteNavPublicData")
                .tag(TAG)
                .description("公开的导航页数据：可见分类与链接、搜索引擎、快捷导航、图标雪碧图，"
                    + "以及页面展示配置（品牌、背景、配色、页脚备案、SEO）。"
                    + "不包含访问统计与运维字段。")
                .response(responseBuilder()
                    .responseCode("200")
                    .description("导航页公开数据")
                    .implementation(NavPublicView.class))
                .response(responseBuilder()
                    .responseCode("500")
                    .description("服务内部错误")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion(NavConstants.PUBLIC_API_GROUP, NavConstants.API_VERSION);
    }

    Mono<NavPublicView> load(ServerRequest request) {
        var contextPath = request.exchange().getRequest().getPath().contextPath().value();
        var userAgent = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        var key = new CacheKey(NavQueryService.isMobile(userAgent), contextPath);

        var cached = cache.get(key);
        var now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Mono.just(cached.view());
        }
        return publicViewService.load(userAgent, contextPath)
            .map(NavPublicViewService.LoadedNavView::view)
            .doOnNext(view -> cache.put(key,
                new CachedView(view, Instant.now().plus(CACHE_TTL))));
    }

    private Mono<ServerResponse> nav(ServerRequest request) {
        return load(request)
            .flatMap(view -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                // Search engine URLs depend on the User-Agent (mobile template).
                .varyBy(HttpHeaders.USER_AGENT)
                .bodyValue(view))
            .onErrorResume(error -> {
                log.error("Failed to load public Palette Nav data", error);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .bodyValue(Map.of("message", "加载导航数据失败，请稍后重试"));
            });
    }
}