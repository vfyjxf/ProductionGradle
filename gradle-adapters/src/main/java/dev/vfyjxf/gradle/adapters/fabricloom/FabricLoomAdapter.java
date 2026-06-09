package dev.vfyjxf.gradle.adapters.fabricloom;

import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findExtension;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findDependencyVersion;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findStringProperty;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.hasPlugin;

import dev.vfyjxf.gradle.adapters.DevelopmentArtifactResolver;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironment;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironmentAdapter;
import java.util.List;
import java.util.Optional;
import org.gradle.api.Project;

public final class FabricLoomAdapter implements DevelopmentEnvironmentAdapter {
    private static final String[] PLUGIN_IDS = {
        "fabric-loom", "net.fabricmc.fabric-loom", "net.fabricmc.fabric-loom-remap"
    };
    private static final String[] EXTENSION_NAMES = {"loom", "fabricLoom"};
    private static final List<String> LOADER_CONFIGURATION_NAMES =
            List.of("modImplementation", "modRuntimeOnly", "implementation", "runtimeOnly", "compileOnly");

    private final DevelopmentArtifactResolver artifactResolver = new DevelopmentArtifactResolver();

    @Override
    public boolean isPresent(Project project) {
        return hasPlugin(project, PLUGIN_IDS) && findExtension(project, EXTENSION_NAMES).isPresent();
    }

    @Override
    public DevelopmentEnvironment detect(Project project) {
        Object extension = findExtension(project, EXTENSION_NAMES).orElse(null);
        Optional<String> fallbackArtifactTaskPath = artifactResolver.findTaskPath(project, "remapJar", "jar");

        return new DevelopmentEnvironment(
                "fabric-loom",
                findStringProperty(extension, "minecraftVersion")
                        .or(() -> findDependencyVersion(project, "minecraft", "com.mojang", "minecraft"))
                        .orElse(""),
                "fabric",
                findStringProperty(extension, "loaderVersion", "fabricLoaderVersion")
                        .or(() -> findDependencyVersion(
                                project, LOADER_CONFIGURATION_NAMES, "net.fabricmc", "fabric-loader"))
                        .orElse(""),
                fallbackArtifactTaskPath,
                artifactResolver.warningWhenJarFallback(fallbackArtifactTaskPath));
    }
}
