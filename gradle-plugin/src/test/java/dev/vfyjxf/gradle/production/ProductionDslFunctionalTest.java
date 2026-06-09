package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionDslFunctionalTest {
    @TempDir
    Path projectDir;

    @Test
    void registersProductionRunTasksFromGroovyDsl() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    autoDetect true
                    instanceDir = file("run-production")
                    runs.configureEach {
                        minecraftVersion = "1.21.1"
                        loader = "fabric"
                        loaderVersion = "0.16.10"
                        javaVersion = 21
                        userName = "DevPlayer"
                        mods {
                            includeProject true
                            includeRequiredDependencies true
                            includeOptionalDependencies false
                        }
                    }
                    runs {
                        client {
                            type = "client"
                            jvmArgs "-Xmx2G"
                        }
                        server {
                            type = "server"
                            eula true
                        }
                    }
                }
                """);

        String output = gradleRunner("tasks", "--all", "--stacktrace").build().getOutput();

        assertThat(output)
                .contains(
                        "runProductionClient",
                        "runProductionServer",
                        "prepareProductionClient",
                        "prepareProductionServer",
                        "validateProductionRun")
                .doesNotContain("generateProductionIdeaRuns");
    }

    @Test
    void autoDetectAppliesDevelopmentEnvironmentToDefaultRunsByDefault() throws IOException {
        Path buildSrc = projectDir.resolve("buildSrc");
        Files.createDirectories(buildSrc.resolve("src/main/groovy"));
        Files.writeString(
                buildSrc.resolve("settings.gradle"),
                "rootProject.name = 'auto-detect-build-src'\n");
        Files.writeString(
                buildSrc.resolve("build.gradle"),
                """
                plugins {
                    id "groovy-gradle-plugin"
                }

                gradlePlugin {
                    plugins {
                        fabricLoom {
                            id = "fabric-loom"
                            implementationClass = "FakeFabricLoomPlugin"
                        }
                    }
                }
                """);
        Files.writeString(
                buildSrc.resolve("src/main/groovy/FakeFabricLoomPlugin.groovy"),
                """
                import org.gradle.api.Plugin
                import org.gradle.api.Project

                class FakeFabricLoomPlugin implements Plugin<Project> {
                    void apply(Project project) {
                        project.extensions.add("loom", new FakeLoomExtension())
                    }
                }

                class FakeLoomExtension {
                    String getMinecraftVersion() { "1.21.1" }
                    String getLoaderVersion() { "0.16.10" }
                }
                """);
        writeBuildFile(
                """
                plugins {
                    id "fabric-loom"
                    id "dev.vfyjxf.gradle.production"
                }

                configurations {
                    minecraft
                    modImplementation
                }

                dependencies {
                    minecraft "com.mojang:minecraft:1.21.1"
                    modImplementation "net.fabricmc:fabric-loader:0.16.10"
                }

                production {}
                """);

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        String spec = Files.readString(projectDir.resolve("build/production-gradle/specs/client/launch-spec.json"));
        assertThat(spec)
                .contains("\"minecraftVersion\" : \"1.21.1\"")
                .contains("\"loader\" : \"fabric\"")
                .contains("\"loaderVersion\" : \"0.16.10\"");
    }

    @Test
    void conventionsRunDirectoriesUnderTopLevelInstanceDirectory() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    runs {
                        customRun {}
                        explicitRun {
                            instanceDir = file("explicit-run")
                        }
                    }
                }

                tasks.register("assertProductionDefaults") {
                    doLast {
                        assert production.runs.client.instanceDir.get().asFile == file("run-production/client")
                        assert production.runs.client.workingDir.get().asFile == file("run-production/client")
                        assert production.runs.server.instanceDir.get().asFile == file("run-production/server")
                        assert production.runs.server.workingDir.get().asFile == file("run-production/server")
                        assert production.runs.customRun.instanceDir.get().asFile == file("run-production/customRun")
                        assert production.runs.customRun.workingDir.get().asFile == file("run-production/customRun")
                        assert production.runs.explicitRun.instanceDir.get().asFile == file("explicit-run")
                        assert production.runs.explicitRun.workingDir.get().asFile == file("explicit-run")
                    }
                }
                """);

        gradleRunner("assertProductionDefaults", "--stacktrace").build();
    }

    @Test
    void rejectsGlobalAuthConfiguration() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    auth {
                        microsoft true
                    }
                }
                """);

        BuildResult result = gradleRunner("tasks", "--all", "--stacktrace").buildAndFail();

        assertThat(result.getOutput()).contains("Could not find method auth()");
    }

    @Test
    void failsWhenRunNameHasNoTaskNameSuffix() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    runs {
                        "-" {}
                    }
                }
                """);

        BuildResult result = gradleRunner("tasks", "--all", "--stacktrace").buildAndFail();

        assertThat(result.getOutput())
                .contains("Production run '-' cannot be converted to a task name suffix");
    }

    @Test
    void failsWhenRunNamesUseSameTaskNameSuffix() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    runs {
                        fooBar {}
                        "foo-bar" {}
                    }
                }
                """);

        BuildResult result = gradleRunner("tasks", "--all", "--stacktrace").buildAndFail();

        assertThat(result.getOutput())
                .contains("Production runs 'fooBar' and 'foo-bar' both map to task name suffix 'FooBar'");
    }

    @Test
    void failsWhenRunNameUsesReservedTaskNameSuffix() throws IOException {
        writeBuildFile(
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    runs {
                        run {}
                    }
                }
                """);

        BuildResult result = gradleRunner("tasks", "--all", "--stacktrace").buildAndFail();

        assertThat(result.getOutput())
                .contains("Production run 'run' maps to reserved task name suffix 'Run'");
    }

    private void writeBuildFile(String buildScript) throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                        + "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);"
                        + " repositories { mavenCentral() } }\n"
                        + "rootProject.name = 'production-dsl-test'\n");
        Files.writeString(projectDir.resolve("build.gradle"), buildScript);
    }

    private GradleRunner gradleRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
