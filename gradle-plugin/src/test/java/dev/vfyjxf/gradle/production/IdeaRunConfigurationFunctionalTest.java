package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeaRunConfigurationFunctionalTest {
    @TempDir
    Path projectDir;

    @Test
    void intellijSyncRegistersApplicationRunConfigurationForClientByDefault() throws Exception {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }

                tasks.register("assertProductionIdeaRuns") {
                    doLast {
                        def model = productionIdeaRun("Production Client").toMap()
                        assert model.type == "application"
                        assert model.moduleName == "idea-run-test.productionLauncher"
                        assert model.mainClass == "dev.vfyjxf.gradle.production.bridge.ProductionLauncherBridge"
                        assert model.programParameters.contains("run")
                        assert model.programParameters.contains("--spec")
                        assert model.programParameters.contains(file("build/production-gradle/specs/client/launch-spec.json").absolutePath)
                        assert model.beforeRun.size() == 1
                        assert model.beforeRun[0].type == "gradleTask"
                        assert model.beforeRun[0].taskName.contains("productionLauncherClasses")
                        assert model.beforeRun[0].taskName.contains("generateProductionClientLaunchSpec")
                        assert model.beforeRun[0].projectPath == projectDir.absolutePath
                    }
                }

                def productionIdeaRun(String name) {
                    def ideaModel = project.extensions.getByName("idea")
                    def settings = ideaModel.project.extensions.getByName("settings")
                    def runs = settings.extensions.getByName("runConfigurations")
                    def run = runs.findByName(name)
                    assert run != null
                    return run
                }
                """);

        gradleRunner("-Didea.sync.active=true", "assertProductionIdeaRuns", "--stacktrace").build();
    }

    @Test
    void intellijSyncRegistersGradleRunConfigurationForClientWhenRequested() throws Exception {
        writeBuildFile(
                """
                production {
                    idea {
                        mode = "gradle"
                    }
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }

                tasks.register("assertProductionIdeaRuns") {
                    doLast {
                        def run = productionIdeaRun("Production Client")
                        def model = run.toMap()
                        assert model.type == "gradle"
                        assert model.projectPath == projectDir.absolutePath
                        assert model.taskNames == ["runProductionClient"]
                    }
                }

                def productionIdeaRun(String name) {
                    def ideaModel = project.extensions.getByName("idea")
                    def settings = ideaModel.project.extensions.getByName("settings")
                    def runs = settings.extensions.getByName("runConfigurations")
                    def run = runs.findByName(name)
                    assert run != null
                    return run
                }
                """);

        gradleRunner("-Didea.sync.active=true", "assertProductionIdeaRuns", "--stacktrace").build();
    }

    @Test
    void intellijSyncRegistersSubprojectRunConfigurationOnRootIdeaModel() throws Exception {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                settingsFile("idea-run-subproject-test") + "include 'app'\n");
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                tasks.register("assertProductionIdeaRuns") {
                    doLast {
                        def ideaModel = project.extensions.getByName("idea")
                        def settings = ideaModel.project.extensions.getByName("settings")
                        def runs = settings.extensions.getByName("runConfigurations")
                        def run = runs.findByName("Production :app Client")
                        assert run != null
                        def model = run.toMap()
                        assert model.type == "application"
                        assert model.moduleName == "idea-run-subproject-test.app.productionLauncher"
                        assert model.mainClass == "dev.vfyjxf.gradle.production.bridge.ProductionLauncherBridge"
                        assert model.beforeRun[0].taskName.contains("productionLauncherClasses")
                        assert model.beforeRun[0].taskName.contains("generateProductionClientLaunchSpec")
                        assert model.beforeRun[0].projectPath == file("app").absolutePath
                    }
                }
                """);
        Path appDir = projectDir.resolve("app");
        Files.createDirectories(appDir);
        Files.writeString(
                appDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }
                """);

        gradleRunner("-Didea.sync.active=true", "assertProductionIdeaRuns", "--stacktrace").build();
    }

    @Test
    void productionLauncherBridgeMainIsAvailableOnGeneratedSourceSetRuntimeClasspath() throws Exception {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }

                tasks.register("runProductionLauncherBridgeHelp", JavaExec) {
                    classpath = sourceSets.productionLauncher.runtimeClasspath
                    mainClass = "dev.vfyjxf.gradle.production.bridge.ProductionLauncherBridge"
                    args "--help"
                }
                """);

        String output = gradleRunner("runProductionLauncherBridgeHelp", "--stacktrace").build().getOutput();

        assertThat(output).contains("Usage: production-launcher");
    }

    @Test
    void generationTaskIsNotExposedAsProductionTask() throws Exception {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }
                """);

        String output = gradleRunner("tasks", "--all", "--stacktrace").build().getOutput();

        assertThat(output).doesNotContain("generateProductionIdeaRuns");
    }

    private void writeBuildFile(String body) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), settingsFile("idea-run-test"));
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                """
                        + body);
    }

    private static String settingsFile(String rootName) {
        return "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                + "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);"
                + " repositories { mavenCentral() } }\n"
                + "rootProject.name = '" + rootName + "'\n";
    }

    private GradleRunner gradleRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
