package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherExecFunctionalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "1.21.1";
    private static final String MAIN_CLASS = "dev.vfyjxf.gradle.production.Task16ClientMain";
    private static final String RUN_MARKER = "TASK16_CLIENT_MAIN_RAN";

    @TempDir
    Path projectDir;

    @Test
    void prepareProductionClientInvokesLauncherCliPrepareAfterGeneratingSpec() throws IOException {
        writeBuildFile();
        writeCachedVanillaClient();

        BuildResult result = gradleRunner("prepareProductionClient", "--stacktrace").build();

        assertThat(result.getOutput())
                .contains("[ProductionGradle] Preparing client 1.21.1 (vanilla)")
                .contains("[ProductionGradle] Prepare complete.");
        assertThat(clientLaunchSpecPath()).isRegularFile();
        assertThat(projectPath().resolve("run-production/client/natives")).isDirectory();
        assertThat(projectPath().resolve("run-production/client/logs")).isDirectory();
    }

    @Test
    void runProductionClientInvokesLauncherCliRun() throws IOException {
        writeBuildFile();
        writeCachedVanillaClient();

        BuildResult result = gradleRunner("runProductionClient", "--stacktrace").build();

        assertThat(result.getOutput())
                .contains("[ProductionGradle] Preparing and launching client 1.21.1 (vanilla)")
                .contains("[ProductionGradle] Launching game process.")
                .contains(RUN_MARKER)
                .doesNotContain("> Task :prepareProductionClient");
    }

    @Test
    void printProductionClientCommandInvokesLauncherCliPrintCommand() throws IOException {
        writeBuildFile();
        writeCachedVanillaClient();

        BuildResult result = gradleRunner("printProductionClientCommand", "--stacktrace").build();

        assertThat(result.getOutput()).contains(MAIN_CLASS);
    }

    @Test
    void printProductionClientClasspathInvokesLauncherCliPrintClasspath() throws IOException {
        writeBuildFile();
        Path minecraftJar = writeCachedVanillaClient();

        BuildResult result = gradleRunner("printProductionClientClasspath", "--stacktrace").build();

        assertThat(result.getOutput()).contains(minecraftJar.toString()).doesNotContain(MAIN_CLASS);
    }

    @Test
    void launcherCliReceivesSpecGeneratedForOfflineGradleInvocations() throws IOException {
        writeBuildFile();
        writeCachedVanillaClient();

        BuildResult result = gradleRunner("printProductionClientCommand", "--offline", "--stacktrace").build();

        JsonNode spec = JSON.readTree(clientLaunchSpecPath().toFile());
        assertThat(spec.path("gradle").path("offline").asBoolean()).isTrue();
        assertThat(result.getOutput()).contains(MAIN_CLASS);
    }

    private void writeBuildFile() throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n"
                        + "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);"
                        + " repositories { mavenCentral() } }\n"
                        + "rootProject.name = 'launcher-exec-test'\n");
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                def javaExecutableName = System.getProperty("os.name", "")
                        .toLowerCase()
                        .contains("win") ? "java.exe" : "java"

                production {
                    cacheDir = file("production-cache")
                    instanceDir = file("run-production")
                    idea {
                        enabled = false
                    }
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "vanilla"
                            mainClass = "dev.vfyjxf.gradle.production.Task16ClientMain"
                            javaExecutable = file(new File(new File(System.getProperty("java.home"), "bin"), javaExecutableName))
                        }
                    }
                }
                """);
    }

    private Path writeCachedVanillaClient() throws IOException {
        Path versionDirectory = projectPath()
                .resolve("production-cache/minecraft/versions")
                .resolve(VERSION);
        Files.createDirectories(versionDirectory);
        Files.writeString(
                versionDirectory.resolve(VERSION + ".json"),
                """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "unused.VersionMain",
                  "assetIndex": {
                    "id": "17"
                  },
                  "libraries": [],
                  "arguments": {
                    "jvm": [],
                    "game": [
                      "--username",
                      "${auth_player_name}"
                    ]
                  }
                }
                """);
        return writeTask16ClientJar(versionDirectory.resolve(VERSION + ".jar"));
    }

    private Path writeTask16ClientJar(Path jarPath) throws IOException {
        Path sourceDirectory = projectPath().resolve("generated-main-src");
        Path classesDirectory = projectPath().resolve("generated-main-classes");
        Path sourceFile = sourceDirectory.resolve("dev/vfyjxf/gradle/production/Task16ClientMain.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                """
                package dev.vfyjxf.gradle.production;

                public final class Task16ClientMain {
                    private Task16ClientMain() {
                    }

                    public static void main(String[] args) {
                        System.out.println("TASK16_CLIENT_MAIN_RAN");
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler").isNotNull();
        Files.createDirectories(classesDirectory);
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                classesDirectory.toString(),
                sourceFile.toString());
        assertThat(exitCode).isZero();

        Files.createDirectories(jarPath.getParent());
        Path classFile = classesDirectory.resolve("dev/vfyjxf/gradle/production/Task16ClientMain.class");
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output)) {
            JarEntry entry = new JarEntry("dev/vfyjxf/gradle/production/Task16ClientMain.class");
            jar.putNextEntry(entry);
            Files.copy(classFile, jar);
            jar.closeEntry();
        }
        return jarPath;
    }

    private Path clientLaunchSpecPath() throws IOException {
        return projectPath().resolve("build/production-gradle/specs/client/launch-spec.json");
    }

    private Path projectPath() throws IOException {
        return projectDir.toRealPath();
    }

    private GradleRunner gradleRunner(String... arguments) throws IOException {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withTestKitDir(projectPath().resolve("test-kit").toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }
}
