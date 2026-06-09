package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import picocli.CommandLine.Command;

@Command(name = "validate", description = "Validate a production launch spec.")
public class ValidateCommand extends SpecCommand {
    @Override
    public Integer call() throws Exception {
        LaunchContext context = launchContext();
        LauncherEngine engine = launcherEngine();
        engine.validate(context);
        out().println("valid offline=" + context.offline());
        return 0;
    }
}
