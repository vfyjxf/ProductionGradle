package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LaunchCommand(
        Path javaExecutable,
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment) {
    public LaunchCommand {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
