package dev.vfyjxf.gradle.production.tasks;

import java.io.File;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.TaskAction;

public abstract class PrintResolvedModsTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getModClasspath();

    @TaskAction
    public void printResolvedMods() {
        getModClasspath().getFiles().stream()
                .map(File::getAbsolutePath)
                .forEach(path -> getLogger().lifecycle(path));
    }
}
