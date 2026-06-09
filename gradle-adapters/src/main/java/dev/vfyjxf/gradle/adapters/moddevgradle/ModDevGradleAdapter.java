package dev.vfyjxf.gradle.adapters.moddevgradle;

import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findExtension;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findStringProperty;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.hasPlugin;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.inferMinecraftVersionFromNeoForgeVersion;

import dev.vfyjxf.gradle.adapters.DevelopmentArtifactResolver;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironment;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironmentAdapter;
import java.util.Optional;
import org.gradle.api.Project;

public final class ModDevGradleAdapter implements DevelopmentEnvironmentAdapter {
    private static final String PLUGIN_ID = "net.neoforged.moddev";
    private static final String[] EXTENSION_NAMES = {"neoForge", "neoforge", "modDev", "moddev"};

    private final DevelopmentArtifactResolver artifactResolver = new DevelopmentArtifactResolver();

    @Override
    public boolean isPresent(Project project) {
        return hasPlugin(project, PLUGIN_ID) && findExtension(project, EXTENSION_NAMES).isPresent();
    }

    @Override
    public DevelopmentEnvironment detect(Project project) {
        Object extension = findExtension(project, EXTENSION_NAMES).orElse(null);
        Optional<String> fallbackArtifactTaskPath =
                artifactResolver.findTaskPath(project, "productionJar", "reobfJar", "reobfuscateJar", "jar");
        Optional<String> loaderVersion =
                findStringProperty(extension, "neoForgeVersion", "neoforgeVersion", "loaderVersion", "version");

        return new DevelopmentEnvironment(
                "moddevgradle",
                findStringProperty(extension, "minecraftVersion")
                        .or(() -> loaderVersion.flatMap(version -> inferMinecraftVersionFromNeoForgeVersion(version)))
                        .orElse(""),
                "neoforge",
                loaderVersion.orElse(""),
                fallbackArtifactTaskPath,
                artifactResolver.warningWhenJarFallback(fallbackArtifactTaskPath));
    }
}
