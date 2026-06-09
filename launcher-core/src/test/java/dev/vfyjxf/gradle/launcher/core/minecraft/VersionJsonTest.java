package dev.vfyjxf.gradle.launcher.core.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionJsonTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesVersionMetadataNeededByVanillaResolver() throws Exception {
        Path versionJson = tempDir.resolve("1.21.1.json");
        Files.writeString(versionJson, """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {
                    "id": "17",
                    "sha1": "asset-sha1",
                    "size": 1,
                    "url": "https://example.invalid/index.json"
                  },
                  "downloads": {
                    "client": {
                      "sha1": "client-sha1",
                      "size": 1,
                      "url": "https://example.invalid/client.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:alpha:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/alpha/1.0/alpha-1.0.jar"
                        }
                      },
                      "rules": [
                        {
                          "action": "allow"
                        }
                      ]
                    }
                  ],
                  "arguments": {
                    "game": [
                      "--username",
                      "${auth_player_name}"
                    ],
                    "jvm": [
                      "-cp",
                      "${classpath}"
                    ]
                  }
                }
                """);

        VersionJson version = VersionJson.read(versionJson);

        assertThat(version.id()).isEqualTo("1.21.1");
        assertThat(version.assetIndex().id()).isEqualTo("17");
        assertThat(version.downloads().client().sha1()).isEqualTo("client-sha1");
        assertThat(version.libraries()).hasSize(1);
        assertThat(version.libraries().getFirst().downloads().artifact().path())
                .isEqualTo("org/example/alpha/1.0/alpha-1.0.jar");
        assertThat(version.libraries().getFirst().rules()).containsExactly(java.util.Map.of("action", "allow"));
        assertThat(version.arguments().game()).containsExactly("--username", "${auth_player_name}");
        assertThat(version.arguments().jvm()).containsExactly("-cp", "${classpath}");
    }

    @Test
    void treatsMissingCollectionsAsEmpty() {
        VersionJson version = new VersionJson(
                "1.21.1",
                "release",
                "net.minecraft.client.main.Main",
                null,
                null,
                null,
                null,
                null);

        assertThat(version.libraries()).isEmpty();
        assertThat(version.arguments().game()).isEmpty();
        assertThat(version.arguments().jvm()).isEmpty();
    }

    @Test
    void deeplyCopiesLibraryRules() {
        Map<String, Object> osRule = new LinkedHashMap<>();
        osRule.put("name", "linux");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("action", "allow");
        rule.put("os", osRule);
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule);

        VersionJson.Library library = new VersionJson.Library(
                "org.example:alpha:1.0",
                null,
                rules,
                null);

        rule.put("action", "disallow");
        osRule.put("name", "windows");
        rules.add(Map.of("action", "allow"));

        assertThat(library.rules()).containsExactly(Map.of(
                "action", "allow",
                "os", Map.of("name", "linux")));
        assertThatThrownBy(() -> library.rules().add(Map.of("action", "allow")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> library.rules().getFirst().put("action", "disallow"))
                .isInstanceOf(UnsupportedOperationException.class);

        Object copiedOs = library.rules().getFirst().get("os");
        assertThat(copiedOs).isInstanceOf(Map.class);
        assertThatThrownBy(() -> ((Map<?, ?>) copiedOs).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deeplyCopiesNestedArgumentMapsAndLists() {
        List<Object> value = new ArrayList<>(List.of("--demo"));
        Map<String, Object> osRule = new HashMap<>(Map.of("name", "linux"));
        Map<String, Object> rule = new HashMap<>();
        rule.put("action", "allow");
        rule.put("os", osRule);
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule);
        Map<String, Object> conditionalArgument = new HashMap<>();
        conditionalArgument.put("rules", rules);
        conditionalArgument.put("value", value);
        List<Object> game = new ArrayList<>();
        game.add(conditionalArgument);

        VersionJson.Arguments arguments = new VersionJson.Arguments(game, List.of());

        value.add("--changed");
        osRule.put("name", "windows");
        rule.put("action", "disallow");
        conditionalArgument.put("value", "--changed");
        game.add("--later");

        assertThat(arguments.game()).hasSize(1);
        Object copiedArgument = arguments.game().getFirst();
        assertThat(copiedArgument).isInstanceOf(Map.class);
        Map<?, ?> copiedArgumentMap = (Map<?, ?>) copiedArgument;
        assertThat(copiedArgumentMap.get("value")).isEqualTo(List.of("--demo"));
        assertThat(copiedArgumentMap.get("rules")).isEqualTo(List.of(Map.of(
                "action", "allow",
                "os", Map.of("name", "linux"))));
        assertThatThrownBy(() -> arguments.game().add("--changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(copiedArgumentMap::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) copiedArgumentMap.get("value")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
