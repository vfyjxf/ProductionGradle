package dev.vfyjxf.gradle.launcher.cli;

import dev.vfyjxf.gradle.launcher.cli.commands.PrepareCommand;
import dev.vfyjxf.gradle.launcher.cli.commands.PrintClasspathCommand;
import dev.vfyjxf.gradle.launcher.cli.commands.PrintCommandCommand;
import dev.vfyjxf.gradle.launcher.cli.commands.RunCommand;
import dev.vfyjxf.gradle.launcher.cli.commands.ValidateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "production-launcher",
        mixinStandardHelpOptions = true,
        subcommands = {
                ValidateCommand.class,
                PrepareCommand.class,
                RunCommand.class,
                PrintCommandCommand.class,
                PrintClasspathCommand.class
        })
public class ProductionLauncherMain implements Runnable {
    @Option(names = "--offline", description = "Run without network access when invoked directly.")
    private boolean offline;

    @Spec
    private CommandSpec commandSpec;

    @Override
    public void run() {
        commandSpec.commandLine().usage(commandSpec.commandLine().getOut());
    }

    public boolean offline() {
        return offline;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ProductionLauncherMain()).execute(args);
        System.exit(exitCode);
    }
}
