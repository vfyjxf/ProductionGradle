package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchSpecFunctionalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectDir;

    @Test
    void printProductionClientLaunchSpecWritesJsonFileAndPrintsPath() throws IOException {
        writeBuildFile();
        Path testKitDir = projectPath().resolve("test-kit");

        BuildResult result = gradleRunner(testKitDir, "printProductionClientLaunchSpec", "--stacktrace").build();

        Path specPath = clientLaunchSpecPath();
        assertThat(specPath).isRegularFile();
        assertThat(result.getOutput())
                .contains(specPath.toAbsolutePath().toString())
                .doesNotContain("\"schemaVersion\"");

        JsonNode spec = JSON.readTree(specPath.toFile());
        assertThat(spec.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(spec.path("runName").asText()).isEqualTo("client");
        assertThat(spec.path("type").asText()).isEqualTo("client");
        assertThat(spec.path("minecraftVersion").asText()).isEqualTo("1.21.1");
        assertThat(spec.path("loader").asText()).isEqualTo("fabric");
        assertThat(spec.path("loaderVersion").asText()).isEqualTo("0.16.10");
        assertThat(spec.path("paths").path("cacheDir").asText())
                .startsWith(testKitDir.toString());
        assertThat(spec.path("gradle").path("offline").asBoolean()).isFalse();
        assertThat(spec.path("gradle").path("userHome").asText()).isEqualTo(testKitDir.toString());
    }

    @Test
    void launchSpecRecordsOfflineGradleInvocations() throws IOException {
        writeBuildFile();

        gradleRunner(projectPath().resolve("test-kit"), "printProductionClientLaunchSpec", "--offline", "--stacktrace")
                .build();

        JsonNode spec = JSON.readTree(clientLaunchSpecPath().toFile());
        assertThat(spec.path("gradle").path("offline").asBoolean()).isTrue();
        assertThat(spec.path("gradle").path("userHome").asText())
                .isEqualTo(projectPath().resolve("test-kit").toString());
    }

    @Test
    void runMicrosoftAuthConfiguresOnlyThatRunLaunchSpec() throws IOException {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                        microsoftClient {
                            type = "client"
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                            microsoftAuth = true
                        }
                    }
                }
                """);

        gradleRunner(
                        projectPath().resolve("test-kit"),
                        "printProductionClientLaunchSpec",
                        "printProductionMicrosoftClientLaunchSpec",
                        "--stacktrace")
                .build();

        JsonNode offlineSpec = JSON.readTree(productionSpecPath("client").toFile());
        JsonNode microsoftSpec = JSON.readTree(productionSpecPath("microsoftClient").toFile());
        assertThat(offlineSpec.path("auth").path("mode").asText()).isEqualTo("offline");
        assertThat(offlineSpec.path("auth").path("userName").asText()).isEqualTo("DevPlayer");
        assertThat(microsoftSpec.path("auth").path("mode").asText()).isEqualTo("microsoft");
        assertThat(microsoftSpec.path("auth").path("userName").asText()).isEqualTo("DevPlayer");
        assertThat(microsoftSpec.path("auth").has("tokenCacheKey")).isFalse();
    }

    @Test
    void configureEachCanSetUserNameDefaultsForRuns() throws IOException {
        writeBuildFile(
                """
                production {
                    runs.configureEach {
                        userName = "ConfiguredPlayer"
                    }
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                        }
                    }
                }
                """);

        gradleRunner(projectPath().resolve("test-kit"), "printProductionClientLaunchSpec", "--stacktrace")
                .build();

        JsonNode spec = JSON.readTree(clientLaunchSpecPath().toFile());
        assertThat(spec.path("auth").path("mode").asText()).isEqualTo("offline");
        assertThat(spec.path("auth").path("userName").asText()).isEqualTo("ConfiguredPlayer");
    }

    @Test
    void printProductionClientLaunchSpecFailsWhenMinecraftVersionIsMissing() throws IOException {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            loader = "vanilla"
                        }
                    }
                }
                """);

        BuildResult result = gradleRunner(projectPath().resolve("test-kit"), "printProductionClientLaunchSpec")
                .buildAndFail();

        assertThat(result.getOutput()).contains("minecraftVersion is required");
    }

    @Test
    void printProductionClientLaunchSpecFailsWhenNonVanillaLoaderVersionIsMissing() throws IOException {
        writeBuildFile(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                        }
                    }
                }
                """);

        BuildResult result = gradleRunner(projectPath().resolve("test-kit"), "printProductionClientLaunchSpec")
                .buildAndFail();

        assertThat(result.getOutput()).contains("loaderVersion is required for non-vanilla runs");
    }

    private void writeBuildFile() throws IOException {
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
    }

    private void writeBuildFile(String productionBlock) throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                        + "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);"
                        + " repositories { mavenCentral() } }\n"
                        + "rootProject.name = 'launch-spec-test'\n");
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                """
                        + productionBlock);
    }

    private Path clientLaunchSpecPath() throws IOException {
        return productionSpecPath("client");
    }

    private Path productionSpecPath(String runName) throws IOException {
        return projectPath().resolve("build/production-gradle/specs/" + runName + "/launch-spec.json");
    }

    private Path projectPath() throws IOException {
        return projectDir.toRealPath();
    }

    private GradleRunner gradleRunner(Path testKitDir, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withTestKitDir(testKitDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
