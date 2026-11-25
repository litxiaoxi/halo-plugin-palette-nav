package cn.litx.palettenav.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.dto.NavPublicView;
import cn.litx.palettenav.service.NavPublicViewService;
import cn.litx.palettenav.service.NavStatsService;
import cn.litx.palettenav.service.NavVisitRecorder;
import cn.litx.palettenav.service.NavYiyanService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.router.ModelConst;

/**
 * Guards the flat Thymeleaf model keys. The template reads these names directly, so a
 * rename in the public view must be caught here.
 */
class NavPageRouterTest {

    private NavYiyanService yiyanService;
    private PluginContext pluginContext;
    private NavPageRouter router;

    @BeforeEach
    void setUp() {
        yiyanService = mock(NavYiyanService.class);
        pluginContext = mock(PluginContext.class);
        when(pluginContext.getVersion()).thenReturn("1.0.2");
        router = new NavPageRouter(mock(NavPublicViewService.class), yiyanService,
            mock(NavVisitRecorder.class), mock(NavStatsService.class), pluginContext);
    }

    @Test
    void modelContainsEveryTemplateKey() {
        when(yiyanService.randomQuote()).thenReturn("一言");
        var general = NavSettings.General.defaults();
        general.setShowFooterVisitStats(true);

        var model = router.toModel(view(true), general, "/ctx");

        var expectedKeys = Set.of(ModelConst.TEMPLATE_ID, "categories", "searchEngines",
            "quickLinks", "iconSpriteHtml", "contextPath", "pluginVersion", "defaultColor",
            "defaultNightMode", "showClock", "showYiyan", "showBackgroundByDefault",
            "showCategoryIconsInNav", "yanText", "backgroundUrl", "backgroundVideoUrl",
            "baiduSuggestions", "logoUrl", "brandText", "copyright", "icp", "gonganbei",
            "gonganbeiCode", "icpLogoUrl", "gonganbeiLogoUrl", "seoDescription",
            "seoKeywords", "siteName", "showFooterVisitStats");
        assertTrue(model.keySet().containsAll(expectedKeys),
            () -> "missing keys: " + expectedKeys.stream()
                .filter(key -> !model.containsKey(key)).toList());

        assertEquals("一言", model.get("yanText"));
        assertEquals("/ctx", model.get("contextPath"));
        assertEquals("1.0.2", model.get("pluginVersion"));
        assertEquals(Boolean.TRUE, model.get("showFooterVisitStats"));
        assertEquals("purple", model.get("defaultColor"));
    }

    @Test
    void yiyanIsNotEvaluatedWhenDisabled() {
        var general = NavSettings.General.defaults();
        var model = router.toModel(view(false), general, "");
        assertEquals("", model.get("yanText"));
    }

    @Test
    void videoBackgroundUrlGetsTheContextPathForTheTemplate() {
        var view = view(true);
        view.getAppearance().setBackgroundVideoUrl("/uploads/bg.mp4");

        var model = router.toModel(view, NavSettings.General.defaults(), "/halo");

        assertEquals("/halo/uploads/bg.mp4", model.get("backgroundVideoUrl"));
    }

    private static NavPublicView view(boolean showYiyan) {
        return NavPublicView.builder()
            .categories(List.of())
            .searchEngines(List.of())
            .quickLinks(List.of())
            .spriteHtml("<svg/>")
            .site(NavPublicView.SiteView.builder()
                .siteName("站点")
                .brandText("品牌")
                .logoUrl("/logo.png")
                .siteDescription("描述")
                .siteKeywords("关键词")
                .build())
            .appearance(NavPublicView.AppearanceView.builder()
                .defaultColor("purple")
                .defaultNightMode("auto")
                .showClock(true)
                .showYiyan(showYiyan)
                .showBackgroundByDefault(false)
                .showCategoryIconsInNav(false)
                .backgroundType("image")
                .backgroundUrl("/bg.jpg")
                .backgroundVideoUrl("")
                .build())
            .search(NavPublicView.SearchView.builder()
                .enableBaiduSuggestions(true)
                .build())
            .footer(NavPublicView.FooterView.builder()
                .copyright("<b>x</b>")
                .icp("ICP")
                .gonganbei("公网安备 123号")
                .gonganbeiCode("123")
                .icpLogoUrl("")
                .gonganbeiLogoUrl("")
                .build())
            .build();
    }
}