package dev.vfyjxf.gradle.production.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class PrintLaunchSpecTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSpecFile();

    @TaskAction
    public void printLaunchSpecPath() {
        getLogger().lifecycle(getSpecFile().get().getAsFile().getAbsolutePath());
    }
}
