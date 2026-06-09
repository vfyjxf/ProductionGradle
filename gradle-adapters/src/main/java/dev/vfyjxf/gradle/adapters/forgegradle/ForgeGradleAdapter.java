package dev.vfyjxf.gradle.adapters.forgegradle;

import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findExtension;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findDependencyVersion;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.findStringProperty;
import static dev.vfyjxf.gradle.adapters.internal.GradleModelReflection.hasPlugin;

import dev.vfyjxf.gradle.adapters.DevelopmentArtifactResolver;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironment;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironmentAdapter;
import java.util.Optional;
import org.gradle.api.Project;

public final class ForgeGradleAdapter implements DevelopmentEnvironmentAdapter {
    private static final String PLUGIN_ID = "net.minecraftforge.gradle";
    private static final String[] EXTENSION_NAMES = {"minecraft", "forge"};

    private final DevelopmentArtifactResolver artifactResolver = new DevelopmentArtifactResolver();

    @Override
    public boolean isPresent(Project project) {
        return hasPlugin(project, PLUGIN_ID) && findExtension(project, EXTENSION_NAMES).isPresent();
    }

    @Override
    public DevelopmentEnvironment detect(Project project) {
        Object extension = findExtension(project, EXTENSION_NAMES).orElse(null);
        Optional<String> fallbackArtifactTaskPath =
                artifactResolver.findTaskPath(project, "reobfJar", "reobfuscateJar", "reobfProductionJar", "jar");
        Optional<ForgeVersion> dependencyVersion = findDependencyVersion(project, "minecraft", "net.minecraftforge", "forge")
                .flatMap(ForgeVersion::parse);

        return new DevelopmentEnvironment(
                "forgegradle",
                dependencyVersion.map(ForgeVersion::minecraftVersion)
                        .or(() -> findStringProperty(extension, "minecraftVersion"))
                        .orElse(""),
                "forge",
                dependencyVersion.map(ForgeVersion::forgeVersion)
                        .or(() -> findStringProperty(extension, "forgeVersion", "loaderVersion"))
                        .orElse(""),
                fallbackArtifactTaskPath,
                artifactResolver.warningWhenJarFallback(fallbackArtifactTaskPath));
    }

    private record ForgeVersion(String minecraftVersion, String forgeVersion) {
        private static Optional<ForgeVersion> parse(String version) {
            int separator = version.indexOf('-');
            if (separator <= 0 || separator == version.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new ForgeVersion(version.substring(0, separator), version.substring(separator + 1)));
        }
    }
}
