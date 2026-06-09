package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import picocli.CommandLine.Command;

@Command(name = "run", description = "Validate, prepare, and launch the game process.")
public class RunCommand extends SpecCommand {
    @Override
    public Integer call() throws Exception {
        LaunchContext context = launchContext();
        LauncherEngine engine = launcherEngine();
        out().printf(
                "[ProductionGradle] Preparing and launching %s %s (%s)%n",
                context.spec().type(),
                context.spec().minecraftVersion(),
                context.spec().loader());
        out().flush();
        return engine.launch(context);
    }
}
