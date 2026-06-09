package dev.vfyjxf.gradle.launcher.core.loader;

import java.nio.file.Path;
import java.util.List;

public record PreparedLaunch(List<Path> classpath, List<String> jvmArgs, List<String> gameArgs, String mainClass) {
    public PreparedLaunch {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        gameArgs = gameArgs == null ? List.of() : List.copyOf(gameArgs);
    }
}
