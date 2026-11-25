package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Public, anonymous-readable projection of the {@code /nav} page.
 *
 * <p>This is a standalone contract, intentionally not reusing the Thymeleaf view
 * classes: template-driven changes must not silently alter the JSON that third-party
 * themes parse. Settings fields that are operational ({@code trustedProxyIps},
 * attachment storage policy, visit-stat switches) and visit counters are deliberately
 * absent.</p>
 */
@Data
@Builder
@Schema(name = "NavPublicView", description = "Palette Nav 公开导航页数据")
public class NavPublicView {

    @Schema(description = "可见分类及其可见链接")
    private List<CategoryView> categories;

    @Schema(description = "可见搜索引擎")
    private List<SearchEngineView> searchEngines;

    @Schema(description = "可见快捷导航")
    private List<QuickLinkView> quickLinks;

    /**
     * Inline SVG sprite holding the symbols referenced by {@code iconHtml}. Consumers
     * must insert it into the document, otherwise the {@code <use href="#id">}
     * references render nothing.
     */
    @Schema(description = "图标雪碧图内联 SVG，需注入页面后 iconHtml 才能显示")
    private String spriteHtml;

    /**
     * Prefix already applied by the deployment (for example {@code /halo}). Every
     * site-relative URL in this payload stays root-relative, so consumers must prefix
     * it exactly once: {@code url.startsWith("/") ? contextPath + url : url}.
     */
    @Schema(description = "部署的 context path；所有以 / 开头的地址需拼接此前缀")
    private String contextPath;

    private SiteView site;
    private AppearanceView appearance;
    private SearchView search;
    private FooterView footer;

    @Data
    @Builder
    @Schema(name = "NavPublicCategory")
    public static class CategoryView {
        private String name;
        private String displayName;
        private String iconHtml;
        private List<LinkView> links;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicLink")
    public static class LinkView {
        private String name;
        private String displayName;
        private String url;
        private String iconHtml;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicSearchEngine")
    public static class SearchEngineView {
        private String alias;
        private String displayName;
        private String hint;
        private String iconHtml;
        @Schema(description = "按本次请求的 User-Agent 选定的搜索地址模板")
        private String searchUrl;
        @Schema(description = "移动端搜索地址模板；为空表示没有单独的移动端模板")
        private String mobileSearchUrl;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicQuickLink")
    public static class QuickLinkView {
        private String displayName;
        private String url;
        private boolean openInNewTab;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicSite")
    public static class SiteView {
        @Schema(description = "站点名称（配置为空时回退 Halo 站点标题）")
        private String siteName;
        @Schema(description = "品牌文案")
        private String brandText;
        @Schema(description = "LOGO 地址；站内路径为根相对路径")
        private String logoUrl;
        @Schema(description = "站点描述（配置为空时回退 Halo 站点副标题）")
        private String siteDescription;
        private String siteKeywords;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicAppearance")
    public static class AppearanceView {
        private String defaultColor;
        private String defaultNightMode;
        private boolean showClock;
        private boolean showYiyan;
        private boolean showBackgroundByDefault;
        private boolean showCategoryIconsInNav;
        @Schema(description = "实际生效的背景类型：image 或 video")
        private String backgroundType;
        @Schema(description = "图片背景地址；视频背景时为默认背景图")
        private String backgroundUrl;
        @Schema(description = "视频背景地址；非视频背景时为空字符串")
        private String backgroundVideoUrl;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicSearch")
    public static class SearchView {
        private boolean enableBaiduSuggestions;
    }

    @Data
    @Builder
    @Schema(name = "NavPublicFooter")
    public static class FooterView {
        @Schema(description = "经过白名单净化的页脚版权 HTML")
        private String copyright;
        private String icp;
        private String gonganbei;
        @Schema(description = "公安备案号中的数字部分，用于拼接备案链接")
        private String gonganbeiCode;
        private String icpLogoUrl;
        private String gonganbeiLogoUrl;
    }
}