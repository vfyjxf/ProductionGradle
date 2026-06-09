package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;
import java.util.Map;

public record LaunchMod(String source, String id, String version, Path file, Map<String, String> metadata) {
    public LaunchMod {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
