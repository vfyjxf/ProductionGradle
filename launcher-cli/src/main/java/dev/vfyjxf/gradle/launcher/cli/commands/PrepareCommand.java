package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import picocli.CommandLine.Command;

@Command(name = "prepare", description = "Validate and prepare a production launch.")
public class PrepareCommand extends SpecCommand {
    @Override
    public Integer call() throws Exception {
        LaunchContext context = launchContext();
        LauncherEngine engine = launcherEngine();
        out().printf(
                "[ProductionGradle] Preparing %s %s (%s)%n",
                context.spec().type(),
                context.spec().minecraftVersion(),
                context.spec().loader());
        out().flush();
        engine.prepare(context);
        out().println("[ProductionGradle] Prepare complete.");
        out().flush();
        return 0;
    }
}
