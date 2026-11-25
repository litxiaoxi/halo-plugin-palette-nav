package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import cn.litx.palettenav.web.NavPageData;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import run.halo.app.extension.Extension;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NavQueryServiceTest {

    private static final String CONTEXT_PATH = "/ctx";

    private ReactiveExtensionClient client;
    private NavQueryService service;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        service = new NavQueryService(client, new NavIconRenderer());
    }

    @Test
    void buildsOnlyVisibleCategoriesWithSafeVisibleLinks() {
        var visible = category("cat-a", "常用", 10, null);
        var hidden = category("cat-b", "隐藏分组", 20, false);
        var empty = category("cat-c", "空分组", 30, null);
        var deleting = category("cat-d", "删除中", 40, null);
        deleting.getMetadata().setDeletionTimestamp(Instant.now());

        var safe = link("link-1", "cat-a", "示例", "https://example.com", 10, null);
        var insecure = link("link-2", "cat-a", "脚本", "javascript:alert(1)", 20, null);
        var hiddenLink = link("link-3", "cat-a", "隐藏", "https://hidden.example.com", 30,
            false);
        var orphan = link("link-4", "missing-cat", "孤儿", "https://orphan.example.com", 40,
            null);
        var hiddenCategoryLink = link("link-5", "cat-b", "不可见分类", "https://b.example.com",
            50, null);
        var anchor = link("link-6", "cat-a", "锚点", "#", 60, null);

        stub(NavCategory.class, List.of(visible, hidden, empty, deleting));
        stub(NavLink.class,
            List.of(safe, insecure, hiddenLink, orphan, hiddenCategoryLink, anchor));
        stub(NavSearchEngine.class, List.of());
        stub(NavQuickLink.class, List.of());

        var data = service.loadVisiblePageData("Mozilla/5.0", CONTEXT_PATH).block();

        assertEquals(1, data.getCategories().size());
        var view = data.getCategories().getFirst();
        assertEquals("cat-a", view.getName());
        assertEquals("常用", view.getDisplayName());
        // Order follows the client's linkOrder: sort 10 before sort 60.
        assertEquals(List.of("link-1", "link-6"),
            view.getLinks().stream().map(NavPageData.LinkView::getName).toList());
    }

    @Test
    void categoryAndLinkIconsAreSanitized() {
        var category = category("cat-a", "常用", 10, null);
        category.getSpec().setIcon("<svg><script>alert(1)</script></svg>");
        var emoji = link("link-1", "cat-a", "表情", "https://example.com", 10, null);
        emoji.getSpec().setIcon("🚀");
        var svg = link("link-2", "cat-a", "矢量", "https://example.com/svg", 20, null);
        svg.getSpec().setIcon("<svg viewBox=\"0 0 1 1\"><circle r=\"1\"/></svg>");

        stub(NavCategory.class, List.of(category));
        stub(NavLink.class, List.of(emoji, svg));
        stub(NavSearchEngine.class, List.of());
        stub(NavQuickLink.class, List.of());

        var view = service.loadVisiblePageData(null, CONTEXT_PATH).block()
            .getCategories().getFirst();

        // Category icons have no default: an unsafe value renders nothing at all.
        assertTrue(view.getIconHtml().isEmpty());
        assertFalse(view.getIconHtml().contains("<script"));
        assertTrue(view.getLinks().get(0).getIconHtml().contains("icon-text"));
        assertTrue(view.getLinks().get(1).getIconHtml()
            .startsWith("<img src=\"data:image/svg+xml;base64,"));
    }

    @Test
    void collectsSpriteIdsOnlyForIconsThatAreActuallyRendered() {
        var category = category("cat-a", "常用", 10, null);
        category.getSpec().setIcon(sprite("pn-common"));
        var visible = link("link-1", "cat-a", "示例", "https://example.com", 10, null);
        visible.getSpec().setIcon(sprite("pn-game"));
        var hidden = link("link-2", "cat-a", "隐藏", "https://hidden.example.com", 20, false);
        hidden.getSpec().setIcon(sprite("pn-message"));
        var engine = engine("engine-1", "https://www.example.com/s?q=", null, 10, null);
        engine.getSpec().setIcon(sprite("pn-design"));

        stub(NavCategory.class, List.of(category));
        stub(NavLink.class, List.of(visible, hidden));
        stub(NavSearchEngine.class, List.of(engine));
        stub(NavQuickLink.class, List.of());

        var data = service.loadVisiblePageData(null, CONTEXT_PATH).block();

        assertEquals(Set.of("pn-common", "pn-game", "pn-design"),
            data.getSpriteIds());
    }

    @Test
    void usesDefaultIconForLinksWithoutAnIcon() {
        stub(NavCategory.class, List.of(category("cat-a", "常用", 10, null)));
        stub(NavLink.class, List.of(link("link-1", "cat-a", "示例", "https://example.com",
            10, null)));
        stub(NavSearchEngine.class, List.of());
        stub(NavQuickLink.class, List.of());

        var view = service.loadVisiblePageData(null, CONTEXT_PATH).block()
            .getCategories().getFirst();

        assertTrue(view.getLinks().getFirst().getIconHtml()
            .contains(CONTEXT_PATH + NavConstants.DEFAULT_ICON_ASSET));
    }

    @Test
    void dropsCategoriesWhoseOnlyLinksWereFilteredOut() {
        stub(NavCategory.class, List.of(category("cat-a", "不安全", 10, null)));
        stub(NavLink.class, List.of(
            link("link-1", "cat-a", "脚本", "javascript:alert(1)", 10, null),
            link("link-2", "cat-a", "相对协议", "//evil.example.com", 20, null)));
        stub(NavSearchEngine.class, List.of());
        stub(NavQuickLink.class, List.of());

        assertTrue(service.loadVisiblePageData(null, CONTEXT_PATH).block()
            .getCategories().isEmpty());
    }

    @Test
    void selectsMobileSearchTemplateOnlyForMobileAgents() {
        var engine = engine("engine-1", "https://www.example.com/s?q=",
            "https://m.example.com/s?q=", 10, null);
        stub(NavCategory.class, List.of());
        stub(NavLink.class, List.of());
        stub(NavSearchEngine.class, List.of(engine));
        stub(NavQuickLink.class, List.of());

        var desktop = service.loadVisiblePageData("Mozilla/5.0 (Windows NT 10.0)", CONTEXT_PATH)
            .block().getSearchEngines().getFirst();
        var mobile = service.loadVisiblePageData(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", CONTEXT_PATH)
            .block().getSearchEngines().getFirst();

        assertEquals("https://www.example.com/s?q=", desktop.getSearchUrl());
        assertEquals("https://m.example.com/s?q=", mobile.getSearchUrl());
        // The public JSON exposes the mobile template regardless of the caller UA.
        assertEquals("https://m.example.com/s?q=", desktop.getMobileSearchUrl());
        assertEquals("https://m.example.com/s?q=", mobile.getMobileSearchUrl());
    }

    @Test
    void unsafeMobileTemplateIsNotExposedToThePublicView() {
        var engine = engine("engine-1", "https://www.example.com/s?q=",
            "javascript:alert(1)", 10, null);
        stub(NavCategory.class, List.of());
        stub(NavLink.class, List.of());
        stub(NavSearchEngine.class, List.of(engine));
        stub(NavQuickLink.class, List.of());

        var desktop = service.loadVisiblePageData("Mozilla/5.0 (Windows NT 10.0)", CONTEXT_PATH)
            .block().getSearchEngines().getFirst();

        assertEquals("https://www.example.com/s?q=", desktop.getSearchUrl());
        assertEquals("", desktop.getMobileSearchUrl());
    }

    @Test
    void skipsSearchEnginesWithoutUrlTemplateInsteadOfFailingThePage() {
        // The extension API does not enforce @Schema(requiredMode = REQUIRED), so a
        // spec created outside the console can genuinely lack urlTemplate.
        var missingTemplate = engine("engine-null", null, null, 10, null);
        var missingMobileOnly = engine("engine-mobile", null,
            "https://m.example.com/s?q=", 20, null);
        var safeEngine = engine("engine-safe", "https://safe.example.com/?q=", null, 30,
            null);
        stub(NavCategory.class, List.of());
        stub(NavLink.class, List.of());
        stub(NavSearchEngine.class,
            List.of(missingTemplate, missingMobileOnly, safeEngine));
        stub(NavQuickLink.class, List.of());

        var views = service.loadVisiblePageData("Mozilla/5.0 (Windows NT 10.0)",
            CONTEXT_PATH).block().getSearchEngines();

        assertEquals(List.of("engine-safe"),
            views.stream().map(NavPageData.SearchEngineView::getDisplayName).toList());

        // On mobile the same engine still renders through its mobile template.
        var mobileViews = service.loadVisiblePageData(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", CONTEXT_PATH)
            .block().getSearchEngines();
        assertEquals(List.of("engine-mobile", "engine-safe"),
            mobileViews.stream().map(NavPageData.SearchEngineView::getDisplayName).toList());
    }

    @Test
    void filtersHiddenAndUnsafeSearchEnginesAndQuickLinks() {
        var hiddenEngine = engine("engine-hidden", "https://hidden.example.com/?q=", null, 10,
            false);
        var unsafeEngine = engine("engine-unsafe", "javascript:alert(1)", null, 20, null);
        var safeEngine = engine("engine-safe", "https://safe.example.com/?q=", null, 30, null);
        var deletingEngine = engine("engine-deleting", "https://gone.example.com/?q=", null,
            40, null);
        deletingEngine.getMetadata().setDeletionTimestamp(Instant.now());

        var safeQuickLink = quickLink("quick-1", "关于", "/about", true, 10, null);
        var unsafeQuickLink = quickLink("quick-2", "脚本", "javascript:alert(1)", true, 20,
            null);
        var hiddenQuickLink = quickLink("quick-3", "隐藏", "/hidden", false, 30, false);

        stub(NavCategory.class, List.of());
        stub(NavLink.class, List.of());
        stub(NavSearchEngine.class,
            List.of(hiddenEngine, unsafeEngine, safeEngine, deletingEngine));
        stub(NavQuickLink.class, List.of(safeQuickLink, unsafeQuickLink, hiddenQuickLink));

        var data = service.loadVisiblePageData(null, CONTEXT_PATH).block();

        assertEquals(List.of("engine-safe"),
            data.getSearchEngines().stream().map(NavPageData.SearchEngineView::getAlias)
                .toList());
        assertEquals(List.of("关于"),
            data.getQuickLinks().stream().map(NavPageData.QuickLinkView::getDisplayName)
                .toList());
        assertTrue(data.getQuickLinks().getFirst().isOpenInNewTab());
    }

    @Test
    void ignoresCategoriesWithoutSpecInsteadOfFailingThePage() {
        var broken = new NavCategory();
        broken.setMetadata(metadata("cat-broken"));
        var link = link("link-1", "cat-broken", "示例", "https://example.com", 10, null);

        stub(NavCategory.class, List.of(broken));
        stub(NavLink.class, List.of(link));
        stub(NavSearchEngine.class, List.of());
        stub(NavQuickLink.class, List.of());

        assertTrue(service.loadVisiblePageData(null, CONTEXT_PATH).block()
            .getCategories().isEmpty());
    }

    /**
     * Emulates Halo's client: the predicate and comparator are applied by the
     * store, so the service receives an already filtered and sorted list.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E extends Extension> void stub(Class<E> type, List<E> items) {
        when(client.list(eq(type), any(Predicate.class), any(Comparator.class)))
            .thenAnswer(invocation -> {
                Predicate<E> predicate = invocation.getArgument(1);
                Comparator<E> comparator = invocation.getArgument(2);
                return Flux.fromIterable(items).filter(predicate).sort(comparator);
            });
    }

    private static String sprite(String id) {
        return "<svg class=\"icon\"><use xlink:href=\"#" + id + "\"></use></svg>";
    }

    private static NavCategory category(String name, String displayName, Integer sort,
        Boolean visible) {
        var category = new NavCategory();
        category.setMetadata(metadata(name));
        var spec = new NavCategory.Spec();
        spec.setDisplayName(displayName);
        spec.setSort(sort);
        spec.setVisible(visible);
        category.setSpec(spec);
        return category;
    }

    private static NavLink link(String name, String category, String displayName, String url,
        Integer sort, Boolean visible) {
        var link = new NavLink();
        link.setMetadata(metadata(name));
        var spec = new NavLink.Spec();
        spec.setCategoryName(category);
        spec.setDisplayName(displayName);
        spec.setUrl(url);
        spec.setSort(sort);
        spec.setVisible(visible);
        link.setSpec(spec);
        return link;
    }

    private static NavSearchEngine engine(String name, String urlTemplate,
        String mobileUrlTemplate, Integer sort, Boolean visible) {
        var engine = new NavSearchEngine();
        engine.setMetadata(metadata(name));
        var spec = new NavSearchEngine.Spec();
        spec.setAlias(name);
        spec.setDisplayName(name);
        spec.setUrlTemplate(urlTemplate);
        spec.setMobileUrlTemplate(mobileUrlTemplate);
        spec.setSort(sort);
        spec.setVisible(visible);
        engine.setSpec(spec);
        return engine;
    }

    private static NavQuickLink quickLink(String name, String displayName, String url,
        Boolean openInNewTab, Integer sort, Boolean visible) {
        var link = new NavQuickLink();
        link.setMetadata(metadata(name));
        var spec = new NavQuickLink.Spec();
        spec.setDisplayName(displayName);
        spec.setUrl(url);
        spec.setOpenInNewTab(openInNewTab);
        spec.setSort(sort);
        spec.setVisible(visible);
        link.setSpec(spec);
        return link;
    }

    private static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }
}