package dev.vfyjxf.gradle.launcher.cli.commands;

import dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

abstract class SpecCommand implements Callable<Integer> {
    @ParentCommand
    private ProductionLauncherMain parent;

    @Option(names = "--spec", required = true, description = "Path to the production launch spec JSON.")
    private Path specPath;

    @Spec
    private CommandSpec commandSpec;

    protected LaunchContext launchContext() throws Exception {
        LaunchSpec spec = Json.mapper().readValue(specPath.toFile(), LaunchSpec.class);
        return LaunchContext.directCli(spec, parent.offline());
    }

    protected LauncherEngine launcherEngine() {
        return new LauncherEngine();
    }

    protected PrintWriter out() {
        return commandSpec.commandLine().getOut();
    }
}
