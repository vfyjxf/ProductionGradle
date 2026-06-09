package dev.vfyjxf.gradle.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class DevelopmentEnvironmentServiceTest {
    private final DevelopmentEnvironmentService service = new DevelopmentEnvironmentService();

    @Test
    void fabricLoomPluginIdMapsToFabricLoader() {
        Project project = projectWithPlugin("fabric-loom");
        project.getExtensions().add("loom", new FabricLoomExtension("1.21.1", "0.16.10"));
        project.getTasks().register("remapJar");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.adapterName()).isEqualTo("fabric-loom");
            assertThat(detected.minecraftVersion()).isEqualTo("1.21.1");
            assertThat(detected.loader()).isEqualTo("fabric");
            assertThat(detected.loaderVersion()).isEqualTo("0.16.10");
            assertThat(detected.fallbackArtifactTaskPath()).hasValue(":remapJar");
        });
    }

    @Test
    void fabricLoomNamespacePluginIdMapsToFabricLoader() {
        Project project = projectWithPlugin("net.fabricmc.fabric-loom");
        project.getExtensions().add("loom", new FabricLoomExtension("1.21.1", "0.16.10"));
        project.getTasks().register("jar");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.loader()).isEqualTo("fabric");
            assertThat(detected.fallbackArtifactTaskPath()).hasValue(":jar");
            assertThat(detected.resolutionHints())
                    .containsEntry(
                            "fallbackArtifactWarning",
                            "Falling back to jar; verify it is a production-ready remapped or reobfuscated artifact.");
        });
    }

    @Test
    void fabricLoomRemapPluginIdMapsToFabricLoader() {
        Project project = projectWithPlugin("net.fabricmc.fabric-loom-remap");
        project.getExtensions().add("loom", new FabricLoomExtension("1.21.1", "0.16.10"));

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> assertThat(detected.loader()).isEqualTo("fabric"));
    }

    @Test
    void fabricLoomReadsVersionsFromDeclaredDependencies() {
        Project project = projectWithPlugin("fabric-loom");
        project.getExtensions().add("loom", new Object());
        dependency(project, "minecraft", "com.mojang", "minecraft", "1.21.1");
        dependency(project, "modImplementation", "net.fabricmc", "fabric-loader", "0.16.10");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("1.21.1");
            assertThat(detected.loaderVersion()).isEqualTo("0.16.10");
        });
    }

    @Test
    void forgeGradlePluginIdMapsToForgeLoader() {
        Project project = projectWithPlugin("net.minecraftforge.gradle");
        project.getExtensions().add("minecraft", new ForgeGradleExtension("1.20.1", "47.4.0"));
        project.getTasks().register("reobfJar");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.adapterName()).isEqualTo("forgegradle");
            assertThat(detected.minecraftVersion()).isEqualTo("1.20.1");
            assertThat(detected.loader()).isEqualTo("forge");
            assertThat(detected.loaderVersion()).isEqualTo("47.4.0");
            assertThat(detected.fallbackArtifactTaskPath()).hasValue(":reobfJar");
        });
    }

    @Test
    void forgeGradleReadsVersionsFromMinecraftDependencyCoordinate() {
        Project project = projectWithPlugin("net.minecraftforge.gradle");
        project.getExtensions().add("minecraft", new Object());
        dependency(project, "minecraft", "net.minecraftforge", "forge", "1.20.1-47.4.0");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("1.20.1");
            assertThat(detected.loaderVersion()).isEqualTo("47.4.0");
        });
    }

    @Test
    void neoGradlePluginIdMapsToNeoForgeLoader() {
        Project project = projectWithPlugin("net.neoforged.gradle.userdev");
        project.getExtensions().add("neoForge", new NeoForgeExtension("1.21.1", "21.1.172"));
        project.getTasks().register("productionJar");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.adapterName()).isEqualTo("neogradle");
            assertThat(detected.minecraftVersion()).isEqualTo("1.21.1");
            assertThat(detected.loader()).isEqualTo("neoforge");
            assertThat(detected.loaderVersion()).isEqualTo("21.1.172");
            assertThat(detected.fallbackArtifactTaskPath()).hasValue(":productionJar");
        });
    }

    @Test
    void neoGradleReadsVersionFromNeoForgeDependencyWithoutExtension() {
        Project project = projectWithPlugin("net.neoforged.gradle.userdev");
        dependency(project, "implementation", "net.neoforged", "neoforge", "21.1.172");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("1.21.1");
            assertThat(detected.loaderVersion()).isEqualTo("21.1.172");
            assertThat(detected.loader()).isEqualTo("neoforge");
        });
    }

    @Test
    void neoGradleInfersModernMinecraftVersionFromNeoForgeDependency() {
        Project project = projectWithPlugin("net.neoforged.gradle.userdev");
        dependency(project, "implementation", "net.neoforged", "neoforge", "26.1.0.5-beta");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("26.1");
            assertThat(detected.loaderVersion()).isEqualTo("26.1.0.5-beta");
        });
    }

    @Test
    void modDevGradlePluginIdMapsToNeoForgeLoader() {
        Project project = projectWithPlugin("net.neoforged.moddev");
        project.getExtensions().add("neoForge", new ModDevGradleExtension("1.21.1", "21.1.172"));
        project.getTasks().register("reobfJar");

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.adapterName()).isEqualTo("moddevgradle");
            assertThat(detected.minecraftVersion()).isEqualTo("1.21.1");
            assertThat(detected.loader()).isEqualTo("neoforge");
            assertThat(detected.loaderVersion()).isEqualTo("21.1.172");
            assertThat(detected.fallbackArtifactTaskPath()).hasValue(":reobfJar");
        });
    }

    @Test
    void modDevGradleReadsNeoForgeVersionFromVersionAccessorAndInfersMinecraftVersion() {
        Project project = projectWithPlugin("net.neoforged.moddev");
        project.getExtensions().add("neoForge", new ModDevNeoForgeVersionExtension("20.4.237"));

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("1.20.4");
            assertThat(detected.loaderVersion()).isEqualTo("20.4.237");
        });
    }

    @Test
    void modDevGradleReadsNeoForgeVersionFromPropertyAndInfersModernMinecraftVersion() {
        Project project = projectWithPlugin("net.neoforged.moddev");
        Property<String> neoForgeVersion = project.getObjects().property(String.class);
        neoForgeVersion.set("26.1.0.5-beta");
        project.getExtensions().add("neoForge", new ModDevNeoForgePropertyVersionExtension(neoForgeVersion));

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).hasValueSatisfying(detected -> {
            assertThat(detected.minecraftVersion()).isEqualTo("26.1");
            assertThat(detected.loaderVersion()).isEqualTo("26.1.0.5-beta");
        });
    }

    @Test
    void unknownProjectReturnsEmptyEnvironment() {
        Project project = ProjectBuilder.builder().build();

        Optional<DevelopmentEnvironment> environment = service.detect(project);

        assertThat(environment).isEmpty();
    }

    private Project projectWithPlugin(String pluginId) {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply(pluginId);
        return project;
    }

    private void dependency(Project project, String configurationName, String group, String name, String version) {
        project.getConfigurations().maybeCreate(configurationName);
        project.getDependencies().add(configurationName, group + ":" + name + ":" + version);
    }

    public static final class FabricLoomExtension {
        private final String minecraftVersion;
        private final String loaderVersion;

        FabricLoomExtension(String minecraftVersion, String loaderVersion) {
            this.minecraftVersion = minecraftVersion;
            this.loaderVersion = loaderVersion;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public String getLoaderVersion() {
            return loaderVersion;
        }
    }

    public static final class ForgeGradleExtension {
        private final String minecraftVersion;
        private final String forgeVersion;

        ForgeGradleExtension(String minecraftVersion, String forgeVersion) {
            this.minecraftVersion = minecraftVersion;
            this.forgeVersion = forgeVersion;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public String getForgeVersion() {
            return forgeVersion;
        }
    }

    public static final class NeoForgeExtension {
        private final String minecraftVersion;
        private final String neoForgeVersion;

        NeoForgeExtension(String minecraftVersion, String neoForgeVersion) {
            this.minecraftVersion = minecraftVersion;
            this.neoForgeVersion = neoForgeVersion;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public String getNeoForgeVersion() {
            return neoForgeVersion;
        }
    }

    public static final class ModDevGradleExtension {
        private final String minecraftVersion;
        private final String neoForgeVersion;

        ModDevGradleExtension(String minecraftVersion, String neoForgeVersion) {
            this.minecraftVersion = minecraftVersion;
            this.neoForgeVersion = neoForgeVersion;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public String getNeoForgeVersion() {
            return neoForgeVersion;
        }
    }

    public static final class ModDevNeoForgeVersionExtension {
        private final String version;

        ModDevNeoForgeVersionExtension(String version) {
            this.version = version;
        }

        public String getVersion() {
            return version;
        }
    }

    public static final class ModDevNeoForgePropertyVersionExtension {
        private final Property<String> version;

        ModDevNeoForgePropertyVersionExtension(Property<String> version) {
            this.version = version;
        }

        public Property<String> getVersion() {
            return version;
        }
    }
}
