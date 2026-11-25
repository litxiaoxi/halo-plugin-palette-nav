package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.config.NavSettingsService;
import cn.litx.palettenav.config.NavSiteInfoService;
import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.web.NavPageData;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class NavPublicViewServiceTest {

    private static final String CONTEXT_PATH = "/ctx";

    private NavSettingsService settingsService;
    private NavSiteInfoService siteInfoService;
    private NavQueryService queryService;
    private NavIconSpriteService iconSpriteService;
    private NavPublicViewService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(NavSettingsService.class);
        siteInfoService = mock(NavSiteInfoService.class);
        queryService = mock(NavQueryService.class);
        iconSpriteService = mock(NavIconSpriteService.class);
        service = new NavPublicViewService(settingsService, siteInfoService, queryService,
            iconSpriteService, new NavFooterSanitizer());
    }

    @Test
    void loadPassesUserAgentAndContextPathToTheQuery() {
        var settings = NavSettings.Snapshot.defaults();
        var data = pageData();
        when(settingsService.get()).thenReturn(Mono.just(settings));
        when(siteInfoService.get()).thenReturn(Mono.just(new NavSiteInfoService.SiteInfo(
            "Site", "Sub")));
        when(queryService.loadVisiblePageData("UA", CONTEXT_PATH)).thenReturn(Mono.just(data));
        when(iconSpriteService.render(anyCollection())).thenReturn("<svg/>");

        var loaded = service.load("UA", CONTEXT_PATH).block();

        assertTrue(loaded != null);
        assertEquals("<svg/>", loaded.view().getSpriteHtml());
        assertEquals(settings.getGeneral(), loaded.general());
        verify(queryService).loadVisiblePageData("UA", CONTEXT_PATH);
    }

    @Test
    void siteAndBrandFallBackToHaloSiteSettings() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setSiteName("");
        settings.getGeneral().setSiteDescription("");
        settings.getGeneral().setBrandText("");

        var view = build(settings, new NavSiteInfoService.SiteInfo("Foo - Bar", "Subtitle"));

        assertEquals("Foo - Bar", view.getSite().getSiteName());
        assertEquals("Subtitle", view.getSite().getSiteDescription());
        assertEquals("Foo", view.getSite().getBrandText());
    }

    @Test
    void configuredSiteNameWinsOverHaloTitle() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setSiteName("我的导航");

        var view = build(settings, new NavSiteInfoService.SiteInfo("Foo", "Subtitle"));

        assertEquals("我的导航", view.getSite().getSiteName());
    }

    @Test
    void unsafeLogoFallsBackToDefaultAndOptionalIconsAreDropped() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setLogoUrl("javascript:alert(1)");
        settings.getGeneral().setIcpLogoUrl("javascript:alert(1)");
        settings.getGeneral().setGonganbeiLogoUrl("/uploads/beian.png");

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals(NavConstants.DEFAULT_LOGO_ASSET, view.getSite().getLogoUrl());
        assertEquals("", view.getFooter().getIcpLogoUrl());
        assertEquals("/uploads/beian.png", view.getFooter().getGonganbeiLogoUrl());
    }

    @Test
    void safeLogoIsKept() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setLogoUrl("https://cdn.example.com/logo.png");

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals("https://cdn.example.com/logo.png", view.getSite().getLogoUrl());
    }

    @Test
    void videoBackgroundStaysRootRelativeAndUsesDefaultImage() {
        var settings = NavSettings.Snapshot.defaults();
        var appearance = settings.getAppearance();
        appearance.setBackgroundUrl("/uploads/bg.mp4");
        appearance.setBackgroundType(NavMediaType.VIDEO);

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals(NavMediaType.VIDEO, view.getAppearance().getBackgroundType());
        assertEquals(NavConstants.DEFAULT_BACKGROUND_ASSET,
            view.getAppearance().getBackgroundUrl());
        // Every URL in the payload is root-relative; contextPath is exposed separately.
        assertEquals("/uploads/bg.mp4", view.getAppearance().getBackgroundVideoUrl());
        assertEquals(CONTEXT_PATH, view.getContextPath());
    }

    @Test
    void allUrlsStayRootRelativeAndContextPathIsExposed() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setLogoUrl("/uploads/logo.png");
        settings.getGeneral().setIcpLogoUrl("/uploads/icp.png");
        settings.getGeneral().setGonganbeiLogoUrl("/uploads/gonganbei.png");
        settings.getAppearance().setBackgroundUrl("/uploads/bg.jpg");
        settings.getAppearance().setBackgroundType(NavMediaType.IMAGE);

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals("/uploads/logo.png", view.getSite().getLogoUrl());
        assertEquals("/uploads/icp.png", view.getFooter().getIcpLogoUrl());
        assertEquals("/uploads/gonganbei.png", view.getFooter().getGonganbeiLogoUrl());
        assertEquals("/uploads/bg.jpg", view.getAppearance().getBackgroundUrl());
        assertEquals(CONTEXT_PATH, view.getContextPath());
    }

    @Test
    void mobileSearchUrlIsPassedThrough() {
        var settings = NavSettings.Snapshot.defaults();
        var data = pageData();
        data.getSearchEngines().getFirst().setMobileSearchUrl("https://m.baidu.com/s?wd=%s");

        var view = service.build(settings, new NavSiteInfoService.SiteInfo("", ""), data,
            CONTEXT_PATH);

        assertEquals("https://m.baidu.com/s?wd=%s",
            view.getSearchEngines().getFirst().getMobileSearchUrl());
    }

    @Test
    void unsafeVideoBackgroundDowngradesToImage() {
        var settings = NavSettings.Snapshot.defaults();
        var appearance = settings.getAppearance();
        appearance.setBackgroundUrl("javascript:alert(1)");
        appearance.setBackgroundType(NavMediaType.VIDEO);

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals(NavMediaType.IMAGE, view.getAppearance().getBackgroundType());
        assertEquals(NavConstants.DEFAULT_BACKGROUND_ASSET,
            view.getAppearance().getBackgroundUrl());
        assertEquals("", view.getAppearance().getBackgroundVideoUrl());
    }

    @Test
    void safeImageBackgroundIsKept() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getAppearance().setBackgroundUrl("https://cdn.example.com/bg.jpg");
        settings.getAppearance().setBackgroundType(NavMediaType.IMAGE);

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        assertEquals(NavMediaType.IMAGE, view.getAppearance().getBackgroundType());
        assertEquals("https://cdn.example.com/bg.jpg",
            view.getAppearance().getBackgroundUrl());
        assertEquals("", view.getAppearance().getBackgroundVideoUrl());
    }

    @Test
    void gonganbeiCodeUsesTheFirstDigitRun() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setGonganbei("京公网安备 11010502030123号");

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));
        assertEquals("11010502030123", view.getFooter().getGonganbeiCode());

        settings.getGeneral().setGonganbei("无数字");
        var empty = build(settings, new NavSiteInfoService.SiteInfo("", ""));
        assertEquals("", empty.getFooter().getGonganbeiCode());
    }

    @Test
    void copyrightIsSanitized() {
        var settings = NavSettings.Snapshot.defaults();
        settings.getGeneral().setCopyright(
            "<script>alert(1)</script><b onclick=\"alert(1)\">hi</b>");

        var view = build(settings, new NavSiteInfoService.SiteInfo("", ""));

        var copyright = view.getFooter().getCopyright();
        assertFalse(copyright.contains("<script"));
        assertFalse(copyright.contains("onclick"));
        assertTrue(copyright.contains("hi"));
    }

    @Test
    void publicViewDoesNotCarryOperationalFields() {
        var names = new java.util.HashSet<String>();
        for (var field : NavPublicView.class.getDeclaredFields()) {
            names.add(field.getName());
        }
        assertFalse(names.contains("trustedProxyIps"));
        assertFalse(names.contains("enableVisitStats"));
        assertFalse(names.contains("showFooterVisitStats"));
        assertFalse(names.contains("spriteIds"));
        assertFalse(names.contains("policyName"));
        assertFalse(names.contains("footerTodayPv"));
    }

    private NavPublicView build(NavSettings.Snapshot settings,
        NavSiteInfoService.SiteInfo siteInfo) {
        return service.build(settings, siteInfo, pageData(), CONTEXT_PATH);
    }

    private static NavPageData pageData() {
        var link = NavPageData.LinkView.builder()
            .name("link-1")
            .displayName("示例")
            .url("https://example.com")
            .iconHtml("<img src=\"x\">")
            .build();
        var category = NavPageData.CategoryView.builder()
            .name("cat-1")
            .displayName("常用")
            .iconHtml("")
            .links(List.of(link))
            .build();
        var engine = NavPageData.SearchEngineView.builder()
            .alias("baidu")
            .displayName("百度")
            .hint("百度搜索")
            .iconHtml("")
            .searchUrl("https://www.baidu.com/s?wd=%s")
            .build();
        var quick = NavPageData.QuickLinkView.builder()
            .displayName("GitHub")
            .url("https://github.com")
            .openInNewTab(true)
            .build();
        return NavPageData.builder()
            .categories(List.of(category))
            .searchEngines(List.of(engine))
            .quickLinks(List.of(quick))
            .spriteIds(Set.of("pn-link"))
            .build();
    }

    @Test
    void categoryAndLinkViewsArePassedThrough() {
        var view = build(NavSettings.Snapshot.defaults(),
            new NavSiteInfoService.SiteInfo("", ""));

        assertEquals(1, view.getCategories().size());
        assertEquals("cat-1", view.getCategories().getFirst().getName());
        assertEquals("https://example.com",
            view.getCategories().getFirst().getLinks().getFirst().getUrl());
        assertEquals(1, view.getSearchEngines().size());
        assertEquals(1, view.getQuickLinks().size());
        assertTrue(Arrays.stream(NavPublicView.class.getDeclaredFields())
            .anyMatch(field -> field.getName().equals("spriteHtml")));
    }
}