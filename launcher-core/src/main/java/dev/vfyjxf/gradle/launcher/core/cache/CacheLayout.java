package dev.vfyjxf.gradle.launcher.core.cache;

import java.nio.file.Path;
import java.util.Objects;

public record CacheLayout(
        Path root,
        Path minecraft,
        Path assets,
        Path libraries,
        Path loaders,
        Path modrinth,
        Path curseforge,
        Path auth,
        Path metadata) {
    public static CacheLayout under(Path root) {
        Objects.requireNonNull(root, "root");
        return new CacheLayout(
                root,
                root.resolve("minecraft"),
                root.resolve("assets"),
                root.resolve("libraries"),
                root.resolve("loaders"),
                root.resolve("mods").resolve("modrinth"),
                root.resolve("mods").resolve("curseforge"),
                root.resolve("auth"),
                root.resolve("metadata"));
    }
}
