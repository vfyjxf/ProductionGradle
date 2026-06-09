package dev.vfyjxf.gradle.mods;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModProviderResult(List<ModFile> mods) {
    public ModProviderResult {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    public static ModProviderResult of(List<ModFile> mods) {
        return new ModProviderResult(mods);
    }

    public record ModFile(String source, String id, String version, Path file, Map<String, String> metadata) {
        public ModFile {
            source = requireNotBlank(source, "source");
            id = requireNotBlank(id, "id");
            Objects.requireNonNull(file, "file");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        private static String requireNotBlank(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }
}
