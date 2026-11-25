package cn.litx.palettenav.service;

import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import cn.litx.palettenav.web.NavPageData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Loads the visible navigation data and converts it to Thymeleaf view models.
 */
@Service
@RequiredArgsConstructor
public class NavQueryService {

    private static final Pattern MOBILE_UA = Pattern.compile(
        "(?i)(mobile|android|iphone|ipad|ipod|windows phone|harmonyos)");

    private final ReactiveExtensionClient client;
    private final NavIconRenderer iconRenderer;

    public Mono<NavPageData> loadVisiblePageData(String userAgent, String contextPath) {
        return Mono.zip(
                client.list(NavCategory.class, NavEntitySupport::alive,
                    NavEntitySupport.categoryOrder()).collectList(),
                client.list(NavLink.class, NavEntitySupport::alive,
                    NavEntitySupport.linkOrder()).collectList(),
                client.list(NavSearchEngine.class, NavEntitySupport::alive,
                    NavEntitySupport.engineOrder()).collectList(),
                client.list(NavQuickLink.class, NavEntitySupport::alive,
                    NavEntitySupport.quickLinkOrder()).collectList()
            )
            .map(tuple -> build(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(),
                isMobile(userAgent), contextPath));
    }

    private NavPageData build(List<NavCategory> categories, List<NavLink> links,
        List<NavSearchEngine> engines, List<NavQuickLink> quickLinks,
        boolean mobile, String contextPath) {
        var defaultIcon = contextPath + NavConstants.DEFAULT_ICON_ASSET;
        // Only icons that actually survive the visibility/URL filters are rendered,
        // so collect the sprite ids from the same views instead of from the raw data.
        var spriteIds = new LinkedHashSet<String>();

        // A category created through the extension API without a spec is not renderable;
// keep it out of the page rather than failing the whole /nav render on the
// displayName dereference below.
        var visibleCategories = categories.stream()
            .filter(category -> category.getSpec() != null)
            .filter(category -> NavEntitySupport.isVisible(category.getSpec().getVisible()))
            .toList();

        Set<String> categoryNames = visibleCategories.stream()
            .map(NavEntitySupport::nameOf)
            .collect(Collectors.toSet());

        Map<String, List<NavLink>> linksByCategory = links.stream()
            .filter(link -> NavEntitySupport.isVisible(link.getSpec() == null
                ? null : link.getSpec().getVisible()))
            .filter(link -> link.getSpec() != null)
            .filter(link -> categoryNames.contains(link.getSpec().getCategoryName()))
            .filter(link -> NavUrlValidator.isSafeLinkUrl(link.getSpec().getUrl()))
            .collect(Collectors.groupingBy(
                link -> link.getSpec().getCategoryName(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        var categoryViews = new ArrayList<NavPageData.CategoryView>();
        for (var category : visibleCategories) {
            var categoryName = NavEntitySupport.nameOf(category);
            var categoryLinks = linksByCategory.getOrDefault(categoryName, List.of());
            if (categoryLinks.isEmpty()) {
                continue;
            }
            addSpriteId(spriteIds, category.getSpec().getIcon());
            var linkViews = categoryLinks.stream()
                .map(link -> toLinkView(link, defaultIcon, spriteIds))
                .toList();
            categoryViews.add(NavPageData.CategoryView.builder()
                .name(categoryName)
                .displayName(category.getSpec().getDisplayName())
                .iconHtml(iconRenderer.render(category.getSpec().getIcon(), ""))
                .links(linkViews)
                .build());
        }

        var engineViews = engines.stream()
            .filter(engine -> NavEntitySupport.isVisible(engine.getSpec() == null
                ? null : engine.getSpec().getVisible()))
            .filter(engine -> engine.getSpec() != null)
            .map(engine -> toEngineView(engine, mobile, defaultIcon, spriteIds))
            .filter(java.util.Objects::nonNull)
            .toList();

        var quickLinkViews = quickLinks.stream()
            .filter(link -> NavEntitySupport.isVisible(link.getSpec() == null
                ? null : link.getSpec().getVisible()))
            .filter(link -> link.getSpec() != null)
            .filter(link -> NavUrlValidator.isSafeLinkUrl(link.getSpec().getUrl()))
            .map(link -> NavPageData.QuickLinkView.builder()
                .displayName(link.getSpec().getDisplayName())
                .url(link.getSpec().getUrl())
                .openInNewTab(link.getSpec().getOpenInNewTab() == null
                    || link.getSpec().getOpenInNewTab())
                .build())
            .toList();

        return NavPageData.builder()
            .categories(categoryViews)
            .searchEngines(engineViews)
            .quickLinks(quickLinkViews)
            .spriteIds(Set.copyOf(spriteIds))
            .build();
    }

    private NavPageData.LinkView toLinkView(NavLink link, String defaultIcon,
        Set<String> spriteIds) {
        var spec = link.getSpec();
        addSpriteId(spriteIds, spec.getIcon());
        return NavPageData.LinkView.builder()
            .name(NavEntitySupport.nameOf(link))
            .displayName(spec.getDisplayName())
            .url(spec.getUrl())
            .iconHtml(iconRenderer.render(spec.getIcon(), defaultIcon))
            .build();
    }

    private NavPageData.SearchEngineView toEngineView(NavSearchEngine engine, boolean mobile,
        String defaultIcon, Set<String> spriteIds) {
        var spec = engine.getSpec();
        // @Schema(requiredMode = REQUIRED) is OpenAPI metadata only: Halo's extension
        // store accepts an entity whose urlTemplate is null, and calling trim() on it
        // would turn one malformed search engine into a 500 for the whole public page.
        var mobileTemplate = NavUrlValidator.trim(spec.getMobileUrlTemplate());
        var searchUrl = mobile && !mobileTemplate.isEmpty()
            ? mobileTemplate
            : NavUrlValidator.trim(spec.getUrlTemplate());
        if (!NavUrlValidator.isSafeHttpUrl(searchUrl)) {
            return null;
        }
        // Exposed to the public JSON so server-side callers can choose per visitor
        // instead of inheriting whatever User-Agent their own request used.
        var safeMobileUrl = NavUrlValidator.isSafeHttpUrl(mobileTemplate) ? mobileTemplate : "";
        addSpriteId(spriteIds, spec.getIcon());
        return NavPageData.SearchEngineView.builder()
            .alias(NavUrlValidator.trim(spec.getAlias()))
            .displayName(spec.getDisplayName())
            .hint(NavUrlValidator.trim(spec.getHint()))
            .iconHtml(iconRenderer.render(spec.getIcon(), defaultIcon))
            .searchUrl(searchUrl)
            .mobileSearchUrl(safeMobileUrl)
            .build();
    }

    private void addSpriteId(Set<String> spriteIds, String icon) {
        var id = iconRenderer.spriteId(icon);
        if (!id.isEmpty()) {
            spriteIds.add(id);
        }
    }

    /** Shared with the public JSON cache so its key matches the resolved engine URL. */
    public static boolean isMobile(String userAgent) {
        return userAgent != null && MOBILE_UA.matcher(userAgent).find();
    }
}
