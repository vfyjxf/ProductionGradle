package dev.vfyjxf.gradle.production.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateProductionLauncherBridgeTask extends DefaultTask {
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() throws IOException {
        Path sourceFile = getOutputDirectory().get().getAsFile().toPath()
                .resolve("dev/vfyjxf/gradle/production/bridge/ProductionLauncherBridge.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                """
                package dev.vfyjxf.gradle.production.bridge;

                import java.lang.reflect.InvocationTargetException;
                import java.lang.reflect.Method;

                public final class ProductionLauncherBridge {
                    private static final String LAUNCHER_MAIN =
                            "dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain";

                    private ProductionLauncherBridge() {
                    }

                    public static void main(String[] args) throws Exception {
                        Method main = Class.forName(LAUNCHER_MAIN).getMethod("main", String[].class);
                        try {
                            main.invoke(null, (Object) args);
                        } catch (InvocationTargetException exception) {
                            Throwable cause = exception.getCause();
                            if (cause instanceof Exception checked) {
                                throw checked;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new RuntimeException(cause);
                        }
                    }
                }
                """);
    }
}
