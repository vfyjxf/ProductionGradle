package dev.vfyjxf.gradle.launcher.core.process;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchCommand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GameProcessRunner {
    public int run(LaunchCommand command) throws IOException, InterruptedException {
        return start(command).waitFor();
    }

    public Process start(LaunchCommand command) throws IOException {
        ProcessBuilder builder = processBuilder(command);
        builder.inheritIO();
        return builder.start();
    }

    ProcessBuilder processBuilder(LaunchCommand command) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command.javaExecutable().toString());
        commandLine.addAll(command.arguments());

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory().toFile());
        }
        builder.environment().putAll(command.environment());
        return builder;
    }
}
