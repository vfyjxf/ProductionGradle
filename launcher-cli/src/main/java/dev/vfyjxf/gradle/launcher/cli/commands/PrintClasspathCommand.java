package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import picocli.CommandLine.Command;

import java.nio.file.Path;

@Command(name = "print-classpath", description = "Print one resolved classpath entry per line.")
public class PrintClasspathCommand extends SpecCommand {
    @Override
    public Integer call() throws Exception {
        LaunchContext context = launchContext();
        LauncherEngine engine = launcherEngine();
        PreparedLaunch launch = engine.prepare(context);
        for (Path entry : launch.classpath()) {
            out().println(entry);
        }
        return 0;
    }
}
