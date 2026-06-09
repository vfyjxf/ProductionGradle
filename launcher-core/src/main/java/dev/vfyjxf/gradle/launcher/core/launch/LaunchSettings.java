package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.List;
import java.util.Map;

public record LaunchSettings(
        List<String> jvmArgs,
        List<String> gameArgs,
        Map<String, String> environment,
        String mainClass,
        boolean eulaAccepted) {
    public LaunchSettings {
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        gameArgs = gameArgs == null ? List.of() : List.copyOf(gameArgs);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
