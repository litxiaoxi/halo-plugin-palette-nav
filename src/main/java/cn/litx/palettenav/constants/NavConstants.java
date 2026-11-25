package cn.litx.palettenav.constants;

/**
 * Shared constants for the Palette Nav plugin.
 */
public final class NavConstants {

    public static final String PLUGIN_NAME = "palette-nav";
    public static final String CONSOLE_API_GROUP = "console.api.palette-nav.halo.run";
    /** Public read-only group for themes and other anonymous callers. */
    public static final String PUBLIC_API_GROUP = "api.palette-nav.halo.run";
    public static final String API_VERSION = "v1alpha1";

    public static final String TEMPLATE_NAME = "palette-nav";
    public static final String TEMPLATE_ID = "plugin:" + PLUGIN_NAME + ":" + TEMPLATE_NAME;

    public static final String DEFAULT_ICON_ASSET = "/plugins/palette-nav/assets/nav/img/default-icon.png";
    public static final String DEFAULT_LOGO_ASSET = "/plugins/palette-nav/assets/nav/img/logo-default.png";
    public static final String DEFAULT_BACKGROUND_ASSET = "/plugins/palette-nav/assets/nav/img/background.jpg";

    private NavConstants() {
    }
}
