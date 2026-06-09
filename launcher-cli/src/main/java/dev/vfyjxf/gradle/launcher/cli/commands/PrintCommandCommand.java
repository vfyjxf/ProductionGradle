package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchCommand;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;

@Command(name = "print-command", description = "Print the redacted game process command.")
public class PrintCommandCommand extends SpecCommand {
    @Override
    public Integer call() throws Exception {
        LaunchContext context = launchContext();
        LauncherEngine engine = launcherEngine();
        LaunchCommand command = engine.command(context);
        out().println(format(command));
        return 0;
    }

    private static String format(LaunchCommand command) {
        List<String> parts = new ArrayList<>();
        parts.add(command.javaExecutable().toString());
        parts.addAll(redact(command.arguments()));
        return parts.stream()
                .map(PrintCommandCommand::quoteIfNeeded)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static List<String> redact(List<String> arguments) {
        List<String> redacted = new ArrayList<>(arguments.size());
        boolean redactNext = false;
        for (String argument : arguments) {
            if (redactNext) {
                redacted.add("<redacted>");
                redactNext = false;
                continue;
            }
            if ("--accessToken".equals(argument) || "--access-token".equals(argument)) {
                redacted.add(argument);
                redactNext = true;
                continue;
            }
            String lower = argument.toLowerCase();
            if (lower.contains("token=")) {
                redacted.add(argument.substring(0, argument.indexOf('=') + 1) + "<redacted>");
                continue;
            }
            redacted.add(argument);
        }
        return redacted;
    }

    private static String quoteIfNeeded(String value) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        if (value.chars().noneMatch(Character::isWhitespace)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
