package dev.vfyjxf.gradle.production.tasks;

import java.io.File;
import java.nio.file.Path;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

public abstract class LauncherExecTask extends DefaultTask {
    private static final String MAIN_CLASS = "dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain";

    private final ExecOperations execOperations;

    @Inject
    public LauncherExecTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    @Input
    public abstract Property<String> getCommand();

    @Classpath
    public abstract ConfigurableFileCollection getLauncherClasspath();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSpecFile();

    @TaskAction
    public void runLauncher() {
        execOperations.exec(spec -> {
            spec.setExecutable(currentJavaExecutable().getAbsolutePath());
            spec.setStandardInput(System.in);
            spec.args(
                    "-cp",
                    getLauncherClasspath().getAsPath(),
                    MAIN_CLASS,
                    getCommand().get(),
                    "--spec",
                    getSpecFile().get().getAsFile().getAbsolutePath());
        }).assertNormalExitValue();
    }

    private static File currentJavaExecutable() {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toFile();
    }
}
