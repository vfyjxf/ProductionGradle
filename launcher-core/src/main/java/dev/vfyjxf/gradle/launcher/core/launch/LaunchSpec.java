package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.List;
import java.util.Map;

public record LaunchSpec(
        int schemaVersion,
        String runName,
        String type,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        LaunchPaths paths,
        LaunchJava java,
        LaunchAuth auth,
        List<LaunchMod> mods,
        LaunchSettings launch,
        Map<String, Object> resolutionHints,
        Map<String, Object> gradle) {
    public LaunchSpec {
        mods = mods == null ? List.of() : List.copyOf(mods);
        resolutionHints = ImmutableLaunchData.copyMap(resolutionHints);
        gradle = ImmutableLaunchData.copyMap(gradle);
    }
}
