package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;

public record LaunchPaths(
        Path instanceDir,
        Path workingDir,
        Path cacheDir,
        Path assetsDir,
        Path librariesDir,
        Path nativesDir,
        Path logsDir) {
}
