package dev.vfyjxf.gradle.adapters.neogradle;

import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findExtension;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findDependencyVersion;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findStringProperty;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.hasPlugin;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.inferMinecraftVersionFromNeoForgeVersion;

import dev.vfyjxf.gradle.adapters.DevelopmentArtifactResolver;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironment;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironmentAdapter;
import java.util.List;
import java.util.Optional;
import org.gradle.api.Project;

public final class NeoGradleAdapter implements DevelopmentEnvironmentAdapter {
    private static final String PLUGIN_ID = "net.neoforged.gradle.userdev";
    private static final String[] EXTENSION_NAMES = {"neoForge", "neoforge", "neoGradle"};
    private static final List<String> NEOFORGE_CONFIGURATION_NAMES =
            List.of("implementation", "runtimeOnly", "compileOnly", "modImplementation");

    private final DevelopmentArtifactResolver artifactResolver = new DevelopmentArtifactResolver();

    @Override
    public boolean isPresent(Project project) {
        return hasPlugin(project, PLUGIN_ID)
                && (findExtension(project, EXTENSION_NAMES).isPresent()
                        || findNeoForgeDependencyVersion(project).isPresent());
    }

    @Override
    public DevelopmentEnvironment detect(Project project) {
        Object extension = findExtension(project, EXTENSION_NAMES).orElse(null);
        Optional<String> fallbackArtifactTaskPath =
                artifactResolver.findTaskPath(project, "productionJar", "reobfJar", "reobfuscateJar", "jar");
        Optional<String> loaderVersion = findNeoForgeDependencyVersion(project)
                .or(() -> findStringProperty(extension, "neoForgeVersion", "neoforgeVersion", "loaderVersion"));

        return new DevelopmentEnvironment(
                "neogradle",
                findStringProperty(extension, "minecraftVersion")
                        .or(() -> loaderVersion.flatMap(version -> inferMinecraftVersionFromNeoForgeVersion(version)))
                        .orElse(""),
                "neoforge",
                loaderVersion.orElse(""),
                fallbackArtifactTaskPath,
                artifactResolver.warningWhenJarFallback(fallbackArtifactTaskPath));
    }

    private static Optional<String> findNeoForgeDependencyVersion(Project project) {
        return findDependencyVersion(project, NEOFORGE_CONFIGURATION_NAMES, "net.neoforged", "neoforge");
    }
}
