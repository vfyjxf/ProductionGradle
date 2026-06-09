package dev.vfyjxf.gradle.launcher.core.loader;

import dev.vfyjxf.gradle.launcher.core.loader.vanilla.VanillaResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoaderResolverRegistryTest {
    @Test
    void resolvesVanillaResolver() {
        LoaderResolverRegistry registry = new LoaderResolverRegistry(List.of(new VanillaResolver()));

        assertThat(registry.resolverFor("vanilla")).isInstanceOf(VanillaResolver.class);
    }

    @Test
    void rejectsUnknownLoader() {
        LoaderResolverRegistry registry = new LoaderResolverRegistry(List.of(new VanillaResolver()));

        assertThatThrownBy(() -> registry.resolverFor("unknown")).hasMessageContaining("unknown loader");
    }
}
