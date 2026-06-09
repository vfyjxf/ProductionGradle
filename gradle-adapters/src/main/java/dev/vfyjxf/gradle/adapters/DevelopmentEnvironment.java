package dev.vfyjxf.gradle.adapters;

import java.util.Map;
import java.util.Optional;

public record DevelopmentEnvironment(
        String adapterName,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        Optional<String> fallbackArtifactTaskPath,
        Map<String, String> resolutionHints) {
    public DevelopmentEnvironment {
        fallbackArtifactTaskPath = fallbackArtifactTaskPath == null ? Optional.empty() : fallbackArtifactTaskPath;
        resolutionHints = resolutionHints == null ? Map.of() : Map.copyOf(resolutionHints);
    }
}
