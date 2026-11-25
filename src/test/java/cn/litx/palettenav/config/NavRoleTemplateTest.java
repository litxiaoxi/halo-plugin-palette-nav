package cn.litx.palettenav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the least-privilege promise of the marketplace role templates.
 *
 * <p>The manage role only needs to read and update its own settings ConfigMap. A
 * resourceNames-less {@code configmaps} rule (especially {@code create}) would let the
 * manage role touch every ConfigMap in the installation, which the app store review
 * explicitly rejected. The public role must grant read access to a single resource and
 * aggregate into both the anonymous and authenticated roles.</p>
 */
class NavRoleTemplateTest {

    private static final String MANAGE_ROLE = "palette-nav-role-manage";
    private static final String PUBLIC_ROLE = "palette-nav-role-public";

    @Test
    @SuppressWarnings("unchecked")
    void configMapRulesOnlyTouchThePluginConfigMap() throws Exception {
        var root = roleByName(loadRoles(), MANAGE_ROLE);
        var rules = (List<Map<String, Object>>) root.get("rules");
        assertFalse(rules.isEmpty());

        var configMapRules = rules.stream()
            .filter(rule -> {
                var groups = (List<String>) rule.get("apiGroups");
                var resources = (List<String>) rule.get("resources");
                return groups != null && groups.contains("")
                    && resources != null && resources.contains("configmaps");
            })
            .toList();

        assertTrue(configMapRules.stream().anyMatch(rule ->
            List.of("get", "update").equals(rule.get("verbs"))));
        for (var rule : configMapRules) {
            assertEquals(List.of(NavSettings.CONFIG_MAP_NAME), rule.get("resourceNames"),
                "configmaps rules must be scoped with resourceNames");
            var verbs = (List<String>) rule.get("verbs");
            assertFalse(verbs.contains("create"), "create cannot be scoped by name");
            assertFalse(verbs.contains("delete"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicRoleIsMinimalAndAggregatesToAnonymousAndAuthenticated() throws Exception {
        var roles = loadRoles();
        assertEquals(2, roles.size(), "only the manage and public roles are expected");

        var root = roleByName(roles, PUBLIC_ROLE);
        var metadata = (Map<String, Object>) root.get("metadata");
        var labels = (Map<String, String>) metadata.get("labels");

        assertEquals("true", labels.get("halo.run/role-template"));
        assertEquals("true", labels.get("halo.run/hidden"));
        assertEquals("true",
            labels.get("rbac.authorization.halo.run/aggregate-to-anonymous"));
        assertEquals("true",
            labels.get("rbac.authorization.halo.run/aggregate-to-authenticated"));

        var rules = (List<Map<String, Object>>) root.get("rules");
        assertEquals(1, rules.size());
        var rule = rules.getFirst();
        assertEquals(List.of("api.palette-nav.halo.run"), rule.get("apiGroups"));
        assertEquals(List.of("nav"), rule.get("resources"));
        assertEquals(List.of("get", "list"), rule.get("verbs"));
        assertFalse(rule.containsKey("nonResourceURLs"));
        assertFalse(((List<String>) rule.get("resources")).contains("*"));
        assertFalse(((List<String>) rule.get("verbs")).contains("*"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadRoles() throws Exception {
        var path = Path.of("src/main/resources/extensions/role-templates.yaml");
        var documents = new Yaml().loadAll(Files.readString(path, StandardCharsets.UTF_8));
        var roles = new ArrayList<Map<String, Object>>();
        for (var document : documents) {
            if (document instanceof Map<?, ?> map) {
                roles.add((Map<String, Object>) map);
            }
        }
        return roles;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> roleByName(List<Map<String, Object>> roles, String name) {
        var role = roles.stream()
            .filter(candidate -> {
                var metadata = (Map<String, Object>) candidate.get("metadata");
                return metadata != null && name.equals(metadata.get("name"));
            })
            .findFirst()
            .orElse(null);
        assertNotNull(role, "role " + name + " must exist");
        return role;
    }
}