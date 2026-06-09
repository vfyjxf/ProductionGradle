package dev.vfyjxf.gradle.launcher.cli;

import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchAuth;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchJava;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchPaths;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSettings;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionLauncherMainTest {
    private static final String VERSION = "1.21.1";

    @TempDir
    Path tempDir;

    @Test
    void validateAcceptsValidSpec() throws Exception {
        Path spec = writeValidSpec("java-bin/java", false);

        int exitCode = command().execute("validate", "--spec", spec.toString());

        assertThat(exitCode).isZero();
    }

    @Test
    void validateRejectsMissingSpec() {
        Path missingSpec = tempDir.resolve("missing.json");

        int exitCode = command().execute("validate", "--spec", missingSpec.toString());

        assertThat(exitCode).isNotZero();
    }

    @Test
    void printCommandIncludesSpecJavaExecutable() throws Exception {
        Path spec = writeValidSpec("custom-java/bin/java", false);
        StringWriter output = new StringWriter();

        int exitCode = command(output).execute("print-command", "--spec", spec.toString());

        assertThat(exitCode).isZero();
        assertThat(output.toString())
                .contains("custom-java/bin/java")
                .contains("net.minecraft.client.main.Main")
                .contains("minecraft-" + VERSION + ".jar");
    }

    @Test
    void printCommandFallsBackToCurrentJvmWhenSpecJavaExecutableIsMissing() throws Exception {
        Path spec = writeValidSpec(null, false);
        StringWriter output = new StringWriter();

        int exitCode = command(output).execute("print-command", "--spec", spec.toString());

        assertThat(exitCode).isZero();
        assertThat(output.toString()).contains(currentJvmJavaExecutable().toString());
    }

    @Test
    void offlineRootOptionOverridesSpecOfflineMetadataInDirectCliMode() throws Exception {
        Path spec = writeValidSpec("java-bin/java", false);
        StringWriter output = new StringWriter();

        int exitCode = command(output).execute("--offline", "validate", "--spec", spec.toString());

        assertThat(exitCode).isZero();
        assertThat(output.toString()).contains("offline=true");
    }

    private CommandLine command() {
        return command(new StringWriter());
    }

    private CommandLine command(StringWriter output) {
        CommandLine commandLine = new CommandLine(new ProductionLauncherMain());
        commandLine.setOut(new PrintWriter(output));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine;
    }

    private Path writeValidSpec(String javaExecutable, boolean gradleOffline) throws Exception {
        Path cacheDir = tempDir.resolve("cache");
        Path runDir = tempDir.resolve("run");
        Path versionDir = cacheDir.resolve("minecraft").resolve("versions").resolve(VERSION);
        Path library = cacheDir.resolve("libraries").resolve("org/example/minecraft/1.0/minecraft-1.0.jar");
        Path clientJar = versionDir.resolve("minecraft-" + VERSION + ".jar");
        Files.createDirectories(library.getParent());
        Files.writeString(library, "library");
        Files.createDirectories(versionDir);
        Files.writeString(clientJar, "client");
        Files.writeString(versionDir.resolve(VERSION + ".json"), versionJson());

        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                VERSION,
                "vanilla",
                null,
                new LaunchPaths(
                        runDir,
                        runDir,
                        cacheDir,
                        cacheDir.resolve("assets"),
                        cacheDir.resolve("libraries"),
                        runDir.resolve("natives"),
                        runDir.resolve("logs")),
                javaExecutable == null ? null : new LaunchJava(Path.of(javaExecutable), 21),
                new LaunchAuth("offline", "CliPlayer", null),
                List.of(),
                new LaunchSettings(List.of("-Xmx1G"), List.of("--demo"), Map.of("CLI_ENV", "value"), null, false),
                Map.of(),
                Map.of("offline", gradleOffline));

        Path specPath = tempDir.resolve("valid.json");
        Json.mapper().writeValue(specPath.toFile(), spec);
        return specPath;
    }

    private Path currentJvmJavaExecutable() {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    private String versionJson() {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "downloads": {
                    "client": {
                      "path": "versions/1.21.1/minecraft-1.21.1.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:minecraft:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/minecraft/1.0/minecraft-1.0.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-cp",
                      "${classpath}"
                    ],
                    "game": [
                      "--username",
                      "${auth_player_name}"
                    ]
                  }
                }
                """;
    }
}
