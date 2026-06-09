package dev.vfyjxf.gradle.launcher.core.cache;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheLayoutTest {
    @Test
    void createsExpectedPathsUnderRoot() {
        Path root = Path.of("build", "test-cache");
        CacheLayout layout = CacheLayout.under(root);

        assertThat(layout.root()).isEqualTo(root);
        assertThat(layout.minecraft()).isEqualTo(root.resolve("minecraft"));
        assertThat(layout.assets()).isEqualTo(root.resolve("assets"));
        assertThat(layout.libraries()).isEqualTo(root.resolve("libraries"));
        assertThat(layout.loaders()).isEqualTo(root.resolve("loaders"));
        assertThat(layout.modrinth()).isEqualTo(root.resolve("mods").resolve("modrinth"));
        assertThat(layout.curseforge()).isEqualTo(root.resolve("mods").resolve("curseforge"));
        assertThat(layout.auth()).isEqualTo(root.resolve("auth"));
        assertThat(layout.metadata()).isEqualTo(root.resolve("metadata"));
    }

    @Test
    void rejectsMissingRoot() {
        assertThatThrownBy(() -> CacheLayout.under(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("root");
    }
}
