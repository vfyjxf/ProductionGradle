package dev.vfyjxf.gradle.launcher.core.launch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchSpecJsonTest {
    @Test
    void writesAndReadsSchemaVersionOneSpec() throws Exception {
        Path javaPath = Path.of("java-bin", "java");
        Path instanceDir = Path.of("run-production", "client");
        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                new LaunchPaths(
                        instanceDir,
                        instanceDir,
                        Path.of(".gradle-home", "caches", "production-gradle"),
                        Path.of(".gradle-home", "caches", "production-gradle", "assets"),
                        Path.of(".gradle-home", "caches", "production-gradle", "libraries"),
                        instanceDir.resolve("natives"),
                        instanceDir.resolve("logs")),
                new LaunchJava(javaPath, 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(new LaunchMod("gradle", "example", "1.0.0", Path.of("build/libs/example.jar"), Map.of("side", "both"))),
                new LaunchSettings(List.of("-Xmx2G"), List.of("--demo"), Map.of("ENV_ONE", "value"), null, false),
                Map.of("adapter", "fabric-loom"),
                Map.of("offline", false));

        String json = Json.mapper().writeValueAsString(spec);
        LaunchSpec parsed = Json.mapper().readValue(json, LaunchSpec.class);
        JsonNode tree = Json.mapper().readTree(json);

        assertThat(parsed).isEqualTo(spec);
        assertThat(parsed.schemaVersion()).isEqualTo(1);
        assertThat(parsed.runName()).isEqualTo("client");
        assertThat(parsed.minecraftVersion()).isEqualTo("1.21.1");
        assertThat(parsed.java().executable()).isEqualTo(javaPath);
        assertThat(parsed.mods()).hasSize(1);
        assertThat(parsed.gradle().get("offline")).isEqualTo(false);
        assertThat(tree.path("runName").asText()).isEqualTo("client");
        assertThat(tree.path("paths").path("instanceDir").asText()).isEqualTo(instanceDir.toString());
        assertThat(tree.path("java").path("executable").asText()).isEqualTo(javaPath.toString());
    }

    @Test
    void defensivelyCopiesMutableCollections() {
        Path instanceDir = Path.of("run-production", "client");
        List<LaunchMod> mods = new ArrayList<>();
        Map<String, Object> resolutionHints = new HashMap<>();
        Map<String, Object> gradle = new HashMap<>();
        LaunchMod mod = new LaunchMod("gradle", "example", "1.0.0", Path.of("build/libs/example.jar"), new HashMap<>(Map.of("side", "both")));
        mods.add(mod);
        resolutionHints.put("adapter", "fabric-loom");
        gradle.put("offline", false);

        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                new LaunchPaths(
                        instanceDir,
                        instanceDir,
                        Path.of(".gradle-home", "caches", "production-gradle"),
                        Path.of(".gradle-home", "caches", "production-gradle", "assets"),
                        Path.of(".gradle-home", "caches", "production-gradle", "libraries"),
                        instanceDir.resolve("natives"),
                        instanceDir.resolve("logs")),
                new LaunchJava(Path.of("java-bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                mods,
                new LaunchSettings(new ArrayList<>(List.of("-Xmx2G")), new ArrayList<>(List.of("--demo")), new HashMap<>(Map.of("ENV_ONE", "value")), null, false),
                resolutionHints,
                gradle);

        mods.clear();
        resolutionHints.put("adapter", "changed");
        gradle.put("offline", true);

        assertThat(spec.mods()).containsExactly(mod);
        assertThat(spec.resolutionHints()).containsEntry("adapter", "fabric-loom");
        assertThat(spec.gradle()).containsEntry("offline", false);
        assertThatThrownBy(() -> spec.mods().add(mod)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.resolutionHints().put("adapter", "changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.gradle().put("offline", true)).isInstanceOf(UnsupportedOperationException.class);

        List<String> jvmArgs = new ArrayList<>(List.of("-Xmx2G"));
        List<String> gameArgs = new ArrayList<>(List.of("--demo"));
        Map<String, String> environment = new HashMap<>(Map.of("ENV_ONE", "value"));
        LaunchSettings settings = new LaunchSettings(jvmArgs, gameArgs, environment, null, false);

        jvmArgs.clear();
        gameArgs.clear();
        environment.put("ENV_ONE", "changed");

        assertThat(settings.jvmArgs()).containsExactly("-Xmx2G");
        assertThat(settings.gameArgs()).containsExactly("--demo");
        assertThat(settings.environment()).containsEntry("ENV_ONE", "value");
        assertThatThrownBy(() -> settings.jvmArgs().add("-Xms1G")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.gameArgs().add("--fullscreen")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.environment().put("ENV_TWO", "value")).isInstanceOf(UnsupportedOperationException.class);

        Map<String, String> metadata = new HashMap<>(Map.of("side", "both"));
        LaunchMod copiedMod = new LaunchMod("gradle", "example", "1.0.0", Path.of("build/libs/example.jar"), metadata);

        metadata.put("side", "client");

        assertThat(copiedMod.metadata()).containsEntry("side", "both");
        assertThatThrownBy(() -> copiedMod.metadata().put("side", "client")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deeplyCopiesLaunchMetadata() {
        List<Object> loaderVersions = new ArrayList<>(List.of("0.16.10"));
        Map<String, Object> loader = new HashMap<>();
        loader.put("versions", loaderVersions);
        loader.put("optional", null);
        Map<String, Object> resolutionHints = new HashMap<>();
        resolutionHints.put("loader", loader);
        resolutionHints.put("nullable", null);
        List<Object> gradleFlags = new ArrayList<>(List.of("offline"));
        Map<String, Object> gradle = new HashMap<>();
        gradle.put("flags", gradleFlags);

        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                null,
                null,
                null,
                null,
                null,
                resolutionHints,
                gradle);

        loaderVersions.add("changed");
        loader.put("id", "fabric");
        gradleFlags.add("changed");

        assertThat(spec.resolutionHints()).containsKey("loader");
        Map<String, Object> copiedLoader = (Map<String, Object>) spec.resolutionHints().get("loader");
        List<Object> copiedVersions = (List<Object>) copiedLoader.get("versions");
        assertThat(spec.resolutionHints()).containsEntry("nullable", null);
        assertThat(copiedLoader).containsEntry("optional", null);
        assertThat(copiedLoader).doesNotContainKey("id");
        assertThat(copiedVersions).containsExactly("0.16.10");
        assertThatThrownBy(() -> copiedLoader.put("id", "fabric"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copiedVersions.add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        List<Object> copiedGradleFlags = (List<Object>) spec.gradle().get("flags");
        assertThat(copiedGradleFlags).containsExactly("offline");
        assertThatThrownBy(() -> copiedGradleFlags.add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonStringLaunchMetadataKeys() {
        Map<Object, Object> nested = new HashMap<>();
        nested.put(1, "fabric");
        Map<String, Object> resolutionHints = new HashMap<>();
        resolutionHints.put("loader", nested);

        assertThatThrownBy(() -> new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                null,
                null,
                null,
                null,
                null,
                resolutionHints,
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsNullCollectionsAsEmptyImmutableCollections() {
        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        LaunchSettings settings = new LaunchSettings(null, null, null, null, false);
        LaunchMod mod = new LaunchMod("gradle", "example", "1.0.0", Path.of("build/libs/example.jar"), null);

        assertThat(spec.mods()).isEmpty();
        assertThat(spec.resolutionHints()).isEmpty();
        assertThat(spec.gradle()).isEmpty();
        assertThatThrownBy(() -> spec.mods().add(mod)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.resolutionHints().put("adapter", "fabric-loom")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.gradle().put("offline", false)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(settings.jvmArgs()).isEmpty();
        assertThat(settings.gameArgs()).isEmpty();
        assertThat(settings.environment()).isEmpty();
        assertThatThrownBy(() -> settings.jvmArgs().add("-Xmx2G")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.gameArgs().add("--demo")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.environment().put("ENV_ONE", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(mod.metadata()).isEmpty();
        assertThatThrownBy(() -> mod.metadata().put("side", "both")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapperReturnsIsolatedCopies() {
        ObjectMapper mapper = Json.mapper();
        mapper.disable(SerializationFeature.INDENT_OUTPUT);

        assertThat(Json.mapper().isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
    }
}
