package dev.vfyjxf.gradle.launcher.core.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchSpecValidatorTest {
    @Test
    void acceptsCompleteClientSpec() {
        assertThatCode(() -> new LaunchSpecValidator().validate(validSpec("client", "fabric")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingLaunchSpec() {
        assertThatThrownBy(() -> new LaunchSpecValidator().validate(null))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("launch spec");
    }

    @Test
    void rejectsMissingType() {
        LaunchSpec spec = validSpec("client", "fabric");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                null,
                spec.minecraftVersion(),
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                spec.launch(),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejectsMissingLoader() {
        LaunchSpec spec = validSpec("client", "fabric");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                spec.minecraftVersion(),
                null,
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                spec.launch(),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("loader");
    }

    @Test
    void rejectsMissingMinecraftVersion() {
        LaunchSpec spec = validSpec("client", "fabric");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                "",
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                spec.launch(),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("minecraftVersion");
    }

    @Test
    void rejectsServerWithoutEula() {
        LaunchSpec spec = validSpec("server", "neoforge");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                spec.minecraftVersion(),
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                new LaunchSettings(
                        spec.launch().jvmArgs(),
                        spec.launch().gameArgs(),
                        spec.launch().environment(),
                        spec.launch().mainClass(),
                        false),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("eula");
    }

    @Test
    void rejectsMicrosoftAuthOnServerRuns() {
        LaunchSpec spec = validSpec("server", "neoforge");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                spec.minecraftVersion(),
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                new LaunchAuth("microsoft", "DevPlayer", null),
                spec.mods(),
                spec.launch(),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("microsoft auth is only supported for client runs");
    }

    private static LaunchSpec validSpec(String type, String loader) {
        return new LaunchSpec(
                1,
                type,
                type,
                "1.21.1",
                loader,
                "1.0.0",
                new LaunchPaths(
                        Path.of("run-production/" + type),
                        Path.of("run-production/" + type),
                        Path.of("build/test-cache"),
                        Path.of("build/test-cache/assets"),
                        Path.of("build/test-cache/libraries"),
                        Path.of("run-production/" + type + "/natives"),
                        Path.of("run-production/" + type + "/logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(),
                new LaunchSettings(List.of(), List.of(), Map.of(), null, "server".equals(type)),
                Map.of(),
                Map.of("offline", false));
    }
}
