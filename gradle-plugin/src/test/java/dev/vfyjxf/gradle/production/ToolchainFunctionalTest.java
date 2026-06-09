package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainFunctionalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectDir;

    @Test
    void javaVersionWritesJavaExecutablePathIntoLaunchSpec() throws IOException {
        writeBuildFile(
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
                            javaVersion = 21
                        }
                    }
                }
                """);

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();

        JsonNode spec = readClientLaunchSpec();
        assertThat(spec.path("java").path("executable").asText()).isNotBlank();
        assertThat(spec.path("java").path("version").asInt()).isEqualTo(21);
    }

    @Test
    void explicitJavaExecutableWritesExactPathIntoLaunchSpec() throws IOException {
        Path fakeJava = projectPath().resolve("fake-java");
        Files.writeString(fakeJava, "");
        writeBuildFile(
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
                            javaExecutable = file("fake-java")
                        }
                    }
                }
                """);

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(readClientLaunchSpec().path("java").path("executable").asText())
                .isEqualTo(fakeJava.toAbsolutePath().toString());
    }

    @Test
    void explicitJavaExecutableWinsOverJavaVersion() throws IOException {
        Path fakeJava = projectPath().resolve("fake-java");
        Files.writeString(fakeJava, "");
        writeBuildFile(
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
                            javaVersion = 17
                            javaExecutable = file("fake-java")
                        }
                    }
                }
                """);

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();

        JsonNode spec = readClientLaunchSpec();
        assertThat(spec.path("java").path("executable").asText())
                .isEqualTo(fakeJava.toAbsolutePath().toString());
        assertThat(spec.path("java").path("version").asInt()).isEqualTo(17);
    }

    @Test
    void lateExplicitJavaExecutableWinsAfterLaunchSpecTaskIsRealized() throws IOException {
        Path fakeJava = projectPath().resolve("late-java");
        Files.writeString(fakeJava, "");
        writeBuildFile(
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
                            javaVersion = 21
                        }
                    }
                }

                tasks.named("printProductionClientLaunchSpec").get()

                afterEvaluate {
                    production.runs.client.javaExecutable = file("late-java")
                }
                """);

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(readClientLaunchSpec().path("java").path("executable").asText())
                .isEqualTo(fakeJava.toAbsolutePath().toString());
    }

    @Test
    void explicitJavaExecutablePathDoesNotNeedToExist() throws IOException {
        Path fakeJava = projectPath().resolve("missing-java");
        writeBuildFile(
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
                            javaExecutable = file("missing-java")
                        }
                    }
                }
                """);

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(readClientLaunchSpec().path("java").path("executable").asText())
                .isEqualTo(fakeJava.toAbsolutePath().toString());
    }

    private JsonNode readClientLaunchSpec() throws IOException {
        Path specPath = projectPath().resolve("build/production-gradle/specs/client/launch-spec.json");
        assertThat(specPath).isRegularFile();
        return JSON.readTree(specPath.toFile());
    }

    private void writeBuildFile(String buildScript) throws IOException {
        Path projectPath = projectPath();
        Files.writeString(
                projectPath.resolve("settings.gradle"),
                "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                        + "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);"
                        + " repositories { mavenCentral() } }\n"
                        + "rootProject.name = 'production-toolchain-test'\n");
        Files.writeString(projectPath.resolve("build.gradle"), buildScript);
    }

    private Path projectPath() throws IOException {
        return projectDir.toRealPath();
    }

    private GradleRunner gradleRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
