package cn.litx.palettenav.web;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

/**
 * Server-side view model for {@code /nav}.
 */
@Data
@Builder
public class NavPageData {

    private List<CategoryView> categories;
    private List<SearchEngineView> searchEngines;
    private List<QuickLinkView> quickLinks;
    /** Sprite symbol ids actually referenced by the icons rendered on this page. */
    private Set<String> spriteIds;

    @Data
    @Builder
    public static class CategoryView {
        private String name;
        private String displayName;
        private String iconHtml;
        private List<LinkView> links;
    }

    @Data
    @Builder
    public static class LinkView {
        private String name;
        private String displayName;
        private String url;
        private String iconHtml;
    }

    @Data
    @Builder
    public static class SearchEngineView {
        private String alias;
        private String displayName;
        private String hint;
        private String iconHtml;
        private String searchUrl;
        /**
         * Safe mobile search template, or an empty string when none is configured.
         * The public JSON view exposes it so server-side consumers can pick the
         * template themselves instead of relying on the request's User-Agent.
         */
        private String mobileSearchUrl;
    }

    @Data
    @Builder
    public static class QuickLinkView {
        private String displayName;
        private String url;
        private boolean openInNewTab;
    }
}
