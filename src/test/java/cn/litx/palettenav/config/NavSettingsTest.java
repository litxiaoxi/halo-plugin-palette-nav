package cn.litx.palettenav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.litx.palettenav.config.NavSettings.Appearance;
import org.junit.jupiter.api.Test;

class NavSettingsTest {

    @Test
    void defaultsAreSafe() {
        var snapshot = NavSettings.Snapshot.defaults();
        assertEquals("上网导航", snapshot.getGeneral().getSiteName());
        assertEquals("Palette Nav", snapshot.getGeneral().getBrandText());
        assertEquals("purple", snapshot.getAppearance().getDefaultColor());
        assertEquals("auto", snapshot.getAppearance().getDefaultNightMode());
        assertTrue(snapshot.getAppearance().getShowClock());
        assertTrue(snapshot.getAppearance().getShowYiyan());
        assertFalse(snapshot.getAppearance().getShowBackgroundByDefault());
        assertFalse(snapshot.getAppearance().getShowCategoryIconsInNav());
        assertTrue(snapshot.getSearch().getEnableBaiduSuggestions());
        assertEquals("", snapshot.getIcons().getPolicyName());
        assertEquals("", snapshot.getIcons().getGroupName());
        assertTrue(snapshot.getGeneral().getEnableVisitStats());
        assertFalse(snapshot.getGeneral().getShowFooterVisitStats());
        // Record icons are optional: an unconfigured install keeps a text-only footer.
        assertEquals("", snapshot.getGeneral().getIcpLogoUrl());
        assertEquals("", snapshot.getGeneral().getGonganbeiLogoUrl());
    }

    @Test
    void recordLogoUrlsAreTrimmed() {
        var general = new NavSettings.General();
        general.setIcpLogoUrl("  /upload/icp.png  ");
        general.setGonganbeiLogoUrl("  ");
        general.normalized();
        assertEquals("/upload/icp.png", general.getIcpLogoUrl());
        assertEquals("", general.getGonganbeiLogoUrl());
    }

    @Test
    void missingGeneralFieldsUseProductDefaultsButEmptyStringsStayEmpty() {
        var general = new NavSettings.General();
        general.setSiteName("");
        general.normalized();
        assertEquals("", general.getSiteName());
        assertEquals("Palette Nav", general.getBrandText());
        assertTrue(general.getSiteDescription().startsWith("为 Halo 打造"));
    }

    @Test
    void visitStatisticsDefaultsToEnabled() {
        var general = new NavSettings.General();
        general.normalized();
        assertTrue(general.getEnableVisitStats());

        general.setEnableVisitStats(false);
        general.normalized();
        assertFalse(general.getEnableVisitStats());
    }

    @Test
    void footerVisitStatsAreHiddenByDefaultUnlessEnabled() {
        var general = new NavSettings.General();
        general.normalized();
        assertFalse(general.getShowFooterVisitStats());

        general.setShowFooterVisitStats(true);
        general.normalized();
        assertTrue(general.getShowFooterVisitStats());
    }

    @Test
    void iconStorageTargetIsTrimmed() {
        var icons = new NavSettings.Icons();
        icons.setPolicyName("  attachment-policy-1  ");
        icons.setGroupName("  ");
        icons.normalized();
        assertEquals("attachment-policy-1", icons.getPolicyName());
        assertEquals("", icons.getGroupName());
    }

    @Test
    void invalidAppearanceValuesFallBack() {
        var appearance = new Appearance();
        appearance.setDefaultColor("not-a-color");
        appearance.setDefaultNightMode("not-a-mode");
        appearance.normalized();
        assertEquals("purple", appearance.getDefaultColor());
        assertEquals("auto", appearance.getDefaultNightMode());
        assertEquals("image", appearance.getBackgroundType());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyBackgroundVideoMigratesToMergedMedia() {
        var appearance = new Appearance();
        appearance.setBackgroundVideo("https://example.com/background");
        appearance.normalized();
        assertEquals("https://example.com/background", appearance.getBackgroundUrl());
        assertEquals("video", appearance.getBackgroundType());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyBackgroundImageMigratesToMergedMedia() {
        var appearance = new Appearance();
        appearance.setBackgroundImage("/upload/background.png");
        appearance.normalized();
        assertEquals("/upload/background.png", appearance.getBackgroundUrl());
        assertEquals("image", appearance.getBackgroundType());
    }

    @Test
    void backgroundTypeIsInferredFromUrl() {
        var appearance = new Appearance();
        appearance.setBackgroundUrl("https://example.com/background.MP4?token=1");
        appearance.normalized();
        assertEquals("video", appearance.getBackgroundType());
    }

    @Test
    void backgroundIsHiddenByDefaultUnlessEnabled() {
        var appearance = new Appearance();
        appearance.normalized();
        assertFalse(appearance.getShowBackgroundByDefault());

        appearance.setShowBackgroundByDefault(true);
        appearance.normalized();
        assertTrue(appearance.getShowBackgroundByDefault());
    }

    @Test
    void categoryIconsInNavAreHiddenByDefaultUnlessEnabled() {
        var appearance = new Appearance();
        appearance.normalized();
        assertFalse(appearance.getShowCategoryIconsInNav());

        appearance.setShowCategoryIconsInNav(true);
        appearance.normalized();
        assertTrue(appearance.getShowCategoryIconsInNav());
    }

    @Test
    void explicitBackgroundTypeWins() {
        var appearance = new Appearance();
        appearance.setBackgroundUrl("https://example.com/background.mp4");
        appearance.setBackgroundType("image");
        appearance.normalized();
        assertEquals("image", appearance.getBackgroundType());
    }
}
