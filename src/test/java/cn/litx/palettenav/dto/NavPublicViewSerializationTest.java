package cn.litx.palettenav.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public payload must never grow operational or statistics fields by accident.
 */
class NavPublicViewSerializationTest {

    @Test
    void serializesOnlyPublicFields() throws Exception {
        var link = NavPublicView.LinkView.builder()
            .name("link-1")
            .displayName("示例")
            .url("https://example.com")
            .iconHtml("<img src=\"x\">")
            .build();
        var category = NavPublicView.CategoryView.builder()
            .name("cat-1")
            .displayName("常用")
            .iconHtml("")
            .links(List.of(link))
            .build();
        var view = NavPublicView.builder()
            .categories(List.of(category))
            .searchEngines(List.of(NavPublicView.SearchEngineView.builder()
                .alias("baidu")
                .searchUrl("https://www.baidu.com/s?wd=")
                .mobileSearchUrl("https://m.baidu.com/s?wd=")
                .build()))
            .quickLinks(List.of())
            .spriteHtml("<svg/>")
            .contextPath("/halo")
            .site(NavPublicView.SiteView.builder().siteName("站点").build())
            .appearance(NavPublicView.AppearanceView.builder()
                .defaultColor("purple").build())
            .search(NavPublicView.SearchView.builder()
                .enableBaiduSuggestions(true).build())
            .footer(NavPublicView.FooterView.builder().icp("ICP").build())
            .build();

        var json = JsonMapper.shared().writeValueAsString(view);

        for (var key : Set.of("categories", "searchEngines", "quickLinks", "spriteHtml",
            "contextPath", "site", "appearance", "search", "footer",
            "enableBaiduSuggestions", "showClock", "backgroundVideoUrl",
            "mobileSearchUrl", "gonganbeiCode")) {
            assertTrue(json.contains("\"" + key + "\""), "missing " + key);
        }
        for (var leaked : Set.of("spriteIds", "trustedProxyIps", "enableVisitStats",
            "showFooterVisitStats", "policyName", "groupName", "footerTodayPv",
            "footerTodayUv", "footerTotalPv")) {
            assertFalse(json.contains(leaked), "leaked " + leaked);
        }
    }
}