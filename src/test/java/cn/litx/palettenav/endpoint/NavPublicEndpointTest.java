package cn.litx.palettenav.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.service.NavPublicViewService;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.extension.GroupVersion;

class NavPublicEndpointTest {

    private NavPublicViewService publicViewService;
    private NavPublicEndpoint endpoint;

    @BeforeEach
    void setUp() {
        publicViewService = mock(NavPublicViewService.class);
        endpoint = new NavPublicEndpoint(publicViewService);
    }

    @Test
    void exposesThePublicGroupVersion() {
        assertEquals(new GroupVersion("api.palette-nav.halo.run", "v1alpha1"),
            endpoint.groupVersion());
    }

    @Test
    void matchesGetNav() {
        var matched = endpoint.endpoint().route(request()).hasElement().block();
        assertTrue(Boolean.TRUE.equals(matched));
    }

    @Test
    void returnsJsonWithoutStoreAndVariesByUserAgent() {
        var view = NavPublicView.builder().build();
        when(publicViewService.load(any(), any())).thenReturn(Mono.just(
            new NavPublicViewService.LoadedNavView(view, NavSettings.General.defaults())));

        var response = endpoint.endpoint().route(request())
            .flatMap(handler -> handler.handle(request()))
            .block();

        assertTrue(response != null);
        assertEquals(HttpStatus.OK, response.statusCode());
        assertEquals(APPLICATION_JSON, response.headers().getContentType());
        assertEquals("no-store", response.headers().getCacheControl());
        assertTrue(response.headers().getVary().contains(HttpHeaders.USER_AGENT));
    }

    @Test
    void serviceFailureBecomesInternalServerError() {
        when(publicViewService.load(any(), any())).thenReturn(
            Mono.error(new IllegalStateException("secret detail")));

        var response = endpoint.endpoint().route(request())
            .flatMap(handler -> handler.handle(request()))
            .block();

        assertTrue(response != null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode());
        assertEquals(APPLICATION_JSON, response.headers().getContentType());
    }

    @Test
void repeatedRequestsReuseTheCachedView() {
        when(publicViewService.load(any(), any())).thenReturn(Mono.just(loadedView()));

        endpoint.load(request("Mozilla/5.0")).block();
        endpoint.load(request("Mozilla/5.0")).block();

        verify(publicViewService, times(1)).load(any(), any());
    }

    @Test
    void mobileAndDesktopUseSeparateCacheEntries() {
        when(publicViewService.load(any(), any())).thenReturn(Mono.just(loadedView()));

        endpoint.load(request("Mozilla/5.0")).block();
        endpoint.load(request("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")).block();

        verify(publicViewService, times(2)).load(any(), any());
    }

    private static NavPublicViewService.LoadedNavView loadedView() {
        return new NavPublicViewService.LoadedNavView(NavPublicView.builder().build(),
            NavSettings.General.defaults());
    }

    private static MockServerRequest request() {
        return request("Mozilla/5.0");
    }

    private static MockServerRequest request(String userAgent) {
        // Halo mounts the route under /apis/<group>/<version>; the function itself is relative.
        var uri = URI.create("http://localhost/nav");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(uri.toString())
            .header(HttpHeaders.USER_AGENT, userAgent)
            .build());
        return MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(uri)
            .header(HttpHeaders.USER_AGENT, userAgent)
            .exchange(exchange)
            .build();
    }
}