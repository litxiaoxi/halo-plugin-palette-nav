package cn.litx.palettenav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NavSettingsServiceTest {

    @Test
    void readsAllGroupsFromTheConfigMap() {
        var data = Map.of(
            NavSettings.GROUP_GENERAL,
            "{\"siteName\":\"My Nav\",\"enableVisitStats\":false,"
                + "\"icpLogoUrl\":\"/upload/icp.png\","
                + "\"gonganbeiLogoUrl\":\"/upload/gonganbei.png\"}",
            NavSettings.GROUP_APPEARANCE,
            "{\"defaultColor\":\"blue\",\"defaultNightMode\":\"dark\","
                + "\"showBackgroundByDefault\":true,\"showCategoryIconsInNav\":true,"
                + "\"backgroundUrl\":\"/upload/bg.png\",\"backgroundType\":\"image\"}",
            NavSettings.GROUP_SEARCH, "{\"enableBaiduSuggestions\":false}",
            NavSettings.GROUP_ICONS, "{\"policyName\":\"p1\",\"groupName\":\"g1\"}"
        );

        var snapshot = serviceWith(data).get().block(Duration.ofSeconds(5));

        assertNotNull(snapshot);
        assertEquals("My Nav", snapshot.getGeneral().getSiteName());
        assertFalse(snapshot.getGeneral().getEnableVisitStats());
        assertEquals("/upload/icp.png", snapshot.getGeneral().getIcpLogoUrl());
        assertEquals("/upload/gonganbei.png", snapshot.getGeneral().getGonganbeiLogoUrl());
        assertEquals("blue", snapshot.getAppearance().getDefaultColor());
        assertEquals("dark", snapshot.getAppearance().getDefaultNightMode());
        assertEquals("/upload/bg.png", snapshot.getAppearance().getBackgroundUrl());
        assertTrue(snapshot.getAppearance().getShowBackgroundByDefault());
        assertTrue(snapshot.getAppearance().getShowCategoryIconsInNav());
        assertFalse(snapshot.getSearch().getEnableBaiduSuggestions());
        assertEquals("p1", snapshot.getIcons().getPolicyName());
        assertEquals("g1", snapshot.getIcons().getGroupName());
    }

    @Test
    void fallsBackToDefaultsOnlyForTheMalformedGroup() {
        var data = Map.of(
            NavSettings.GROUP_GENERAL, "{\"siteName\":\"Custom\"}",
            NavSettings.GROUP_SEARCH, "{not-json"
        );

        var snapshot = serviceWith(data).get().block(Duration.ofSeconds(5));

        assertNotNull(snapshot);
        assertEquals("Custom", snapshot.getGeneral().getSiteName());
        // Defaults survive, and the other groups still get their own defaults.
        assertTrue(snapshot.getSearch().getEnableBaiduSuggestions());
        assertEquals("purple", snapshot.getAppearance().getDefaultColor());
    }

    @Test
    void ignoresRemovedFooterCodeInStoredSettings() {
        // Older versions stored customFooterCode in the general group. The field was
        // removed, but its presence must not make the whole group fall back to defaults.
        var data = Map.of(
            NavSettings.GROUP_GENERAL,
            "{\"siteName\":\"Legacy Nav\",\"customFooterCode\":\"<script>alert(1)</script>\"}"
        );

        var snapshot = serviceWith(data).get().block(Duration.ofSeconds(5));

        assertNotNull(snapshot);
        assertEquals("Legacy Nav", snapshot.getGeneral().getSiteName());
    }

    @Test
    void fallsBackToDefaultsWhenTheConfigMapIsMissing() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME))
            .thenReturn(Mono.empty());

        var snapshot = new NavSettingsService(client).get().block(Duration.ofSeconds(5));

        assertNotNull(snapshot);
        assertEquals(NavSettings.General.defaults().getSiteName(),
            snapshot.getGeneral().getSiteName());
        assertEquals(NavSettings.Appearance.defaults().getDefaultColor(),
            snapshot.getAppearance().getDefaultColor());
        assertTrue(snapshot.getSearch().getEnableBaiduSuggestions());
        assertEquals("", snapshot.getIcons().getPolicyName());
    }

    private static NavSettingsService serviceWith(Map<String, String> data) {
        var client = mock(ReactiveExtensionClient.class);
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(NavSettings.CONFIG_MAP_NAME);
        configMap.setMetadata(metadata);
        configMap.setData(data);
        when(client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME))
            .thenReturn(Mono.just(configMap));
        return new NavSettingsService(client);
    }
}
