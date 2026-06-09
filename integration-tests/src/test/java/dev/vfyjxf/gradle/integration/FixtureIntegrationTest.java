package dev.vfyjxf.gradle.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FixtureIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIXTURE_OUTPUT_DIRECTORIES = List.of(
            ".gradle",
            ".idea",
            "build",
            "run-production");
    private static final List<String> TASKS = List.of(
            "validateProductionRun",
            "printProductionClientLaunchSpec",
            "printProductionClientCommand");

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureProductionTasksSucceed(String fixtureName) throws IOException {
        Path projectDirectory = Path.of("").toAbsolutePath().normalize();
        Path rootDirectory = rootDirectory(projectDirectory);
        Path workDirectory = rootDirectory
                .resolve("integration-tests/build/tmp/fixture-integration")
                .resolve(fixtureName + "-" + UUID.randomUUID());
        Path fixtureDirectory = workDirectory.resolve("fixture");
        copyDirectory(
                fixtureRoot(projectDirectory).resolve(fixtureName),
                fixtureDirectory,
                FIXTURE_OUTPUT_DIRECTORIES);
        rewriteIncludedBuild(fixtureDirectory.resolve("settings.gradle"), rootDirectory);
        Path testKitDirectory = workDirectory.resolve("test-kit");

        try (ResolverFixtureServer resolverServer = ResolverFixtureServer.start()) {
            for (String task : TASKS) {
                String output = gradleRunner(fixtureDirectory, testKitDirectory, fixtureName, task, resolverServer)
                        .build()
                        .getOutput();

                assertThat(output).as(fixtureName + " " + task).contains("BUILD SUCCESSFUL");
            }
        }
    }

    static Stream<String> fixtures() {
        return Stream.of(
                "fabric-loom-1.21.1",
                "forgegradle-1.21.1",
                "neogradle-1.21.1",
                "moddevgradle-1.21.1");
    }

    private static GradleRunner gradleRunner(
            Path fixtureDirectory,
            Path testKitDirectory,
            String fixtureName,
            String task,
            ResolverFixtureServer resolverServer) {
        List<String> arguments = new ArrayList<>();
        arguments.add(task);
        arguments.add("--stacktrace");
        arguments.add("-Pproduction.minecraftVersionManifestUri=" + resolverServer.uri("/version-manifest.json"));
        arguments.add("-Pproduction.fabricMetaBaseUri=" + resolverServer.uri("/fabric-meta/"));
        arguments.add("-Pproduction.fabricMavenBaseUri=" + resolverServer.uri("/fabric-maven/"));
        arguments.add("-Pproduction.forgeMavenBaseUri=" + resolverServer.uri("/forge-maven/"));
        arguments.add("-Pproduction.neoforgeMavenBaseUri=" + resolverServer.uri("/neoforge-maven/"));
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(fixtureDirectory.toFile())
                .withTestKitDir(testKitDirectory.toFile())
                .withArguments(arguments);
        if ("forgegradle-1.21.1".equals(fixtureName) || "neogradle-1.21.1".equals(fixtureName)) {
            runner.withGradleVersion("8.14.3").withEnvironment(java21Environment());
        }
        return runner;
    }

    private static String artifactVersion(String loaderVersion) {
        int mappedSuffix = loaderVersion.indexOf("_mapped_");
        if (mappedSuffix >= 0) {
            return loaderVersion.substring(0, mappedSuffix);
        }
        return loaderVersion;
    }

    private static String requiredText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String element : path) {
            current = current.path(element);
        }
        if (!current.isTextual() || current.asText().isBlank()) {
            throw new IllegalStateException("launch spec is missing text field: " + String.join(".", path));
        }
        return current.asText();
    }

    private static void writeJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
            // Empty but valid jar; fixture tests prepare and print commands without launching these jars.
        }
    }

    private static Map<String, String> java21Environment() {
        Path javaHome = Path.of(System.getProperty("user.home"))
                .resolve("Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home");
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        if (javaHome.toFile().isDirectory()) {
            environment.put("JAVA_HOME", javaHome.toString());
        }
        return environment;
    }

    private static Path fixtureRoot(Path projectDirectory) {
        Path direct = projectDirectory.resolve("fixtures");
        if (direct.toFile().isDirectory()) {
            return direct;
        }
        return projectDirectory.resolve("integration-tests/fixtures");
    }

    private static Path rootDirectory(Path projectDirectory) {
        Path directory = projectDirectory;
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve(isWindows() ? "gradlew.bat" : "gradlew"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate Gradle wrapper from " + projectDirectory);
    }

    private static void copyDirectory(Path source, Path target, List<String> excludedDirectoryNames)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(source)
                        && excludedDirectoryNames.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rewriteIncludedBuild(Path settingsFile, Path rootDirectory) throws IOException {
        String settings = Files.readString(settingsFile, StandardCharsets.UTF_8);
        Files.writeString(
                settingsFile,
                settings.replace("includeBuild(\"../../..\")",
                        "includeBuild(\"" + gradleString(rootDirectory) + "\")"),
                StandardCharsets.UTF_8);
    }

    private static String gradleString(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static final class ResolverFixtureServer implements AutoCloseable {
        private static final String MINECRAFT_VERSION = "1.21.1";
        private static final String FABRIC_LOADER_VERSION = "0.16.10";
        private static final String FORGE_VERSION = "52.0.28";
        private static final String NEOFORGE_VERSION = "21.1.172";

        private final HttpServer server;
        private final URI baseUri;
        private final AtomicInteger requests = new AtomicInteger();

        private ResolverFixtureServer(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        static ResolverFixtureServer start() throws IOException {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ResolverFixtureServer server = new ResolverFixtureServer(httpServer);
            server.registerResponses();
            httpServer.start();
            return server;
        }

        URI uri(String path) {
            return baseUri.resolve(path);
        }

        private void registerResponses() throws IOException {
            byte[] clientJar = jarBytes();
            byte[] fabricLoader = jarBytes();
            byte[] intermediary = jarBytes();
            byte[] forgeInstaller = installerBytes(forgeVersionJson("forgeclient"));
            byte[] forgeUniversal = jarBytes();
            byte[] neoforgeInstaller = installerBytes(neoforgeVersionJson("neoforgeclient"));
            byte[] neoforgeUniversal = jarBytes();
            byte[] bootstrapLauncher = jarBytes();
            String versionJson = minecraftVersionJson(sha1(clientJar));

            response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "url": "%s",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(uri("/version.json"), sha1(versionJson)));
            response("/version.json", versionJson);
            response("/client.jar", clientJar);
            response("/fabric-meta/" + MINECRAFT_VERSION + "/" + FABRIC_LOADER_VERSION + "/profile/json", fabricMetadata());
            response("/fabric-maven/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar", fabricLoader);
            response("/fabric-maven/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar", intermediary);
            response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-installer.jar", forgeInstaller);
            response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar", forgeUniversal);
            response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-installer.jar", neoforgeInstaller);
            response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar", neoforgeUniversal);
            response("/forge-maven/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar", bootstrapLauncher);
            response("/neoforge-maven/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar", bootstrapLauncher);
        }

        private void response(String path, String body) {
            response(path, body.getBytes(StandardCharsets.UTF_8));
        }

        private void response(String path, byte[] body) {
            server.createContext(path, exchange -> {
                requests.incrementAndGet();
                respond(exchange, body);
            });
        }

        private static void respond(HttpExchange exchange, byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private String minecraftVersionJson(String clientSha1) {
            return """
                    {
                      "id": "1.21.1",
                      "type": "release",
                      "mainClass": "net.minecraft.client.main.Main",
                      "downloads": {
                        "client": {
                          "sha1": "%s",
                          "url": "%s"
                        }
                      },
                      "libraries": [],
                      "arguments": {
                        "jvm": [],
                        "game": [
                          "--username",
                          "${auth_player_name}",
                          "--accessToken",
                          "${auth_access_token}"
                        ]
                      }
                    }
                    """.formatted(clientSha1, uri("/client.jar"));
        }

        private static String fabricMetadata() {
            return """
                    {
                      "loader": {
                        "maven": "net.fabricmc:fabric-loader:0.16.10",
                        "version": "0.16.10"
                      },
                      "intermediary": {
                        "maven": "net.fabricmc:intermediary:1.21.1",
                        "version": "1.21.1"
                      },
                      "launcherMeta": {
                        "mainClass": {
                          "client": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                          "server": "net.fabricmc.loader.impl.launch.knot.KnotServer"
                        },
                        "libraries": {
                          "common": [
                            {
                              "name": "net.fabricmc:intermediary:1.21.1"
                            }
                          ],
                          "client": [
                            {
                              "name": "net.fabricmc:fabric-loader:0.16.10"
                            }
                          ],
                          "server": []
                        }
                      }
                    }
                    """;
        }

        private static String forgeVersionJson(String launchTarget) {
            return installerVersionJson(
                    "forge-1.21.1-" + FORGE_VERSION,
                    "net.minecraftforge:forge:1.21.1-" + FORGE_VERSION + ":universal",
                    "net/minecraftforge/forge/1.21.1-" + FORGE_VERSION + "/forge-1.21.1-" + FORGE_VERSION + "-universal.jar",
                    launchTarget);
        }

        private static String neoforgeVersionJson(String launchTarget) {
            return installerVersionJson(
                    "neoforge-" + NEOFORGE_VERSION,
                    "net.neoforged:neoforge:" + NEOFORGE_VERSION + ":universal",
                    "net/neoforged/neoforge/" + NEOFORGE_VERSION + "/neoforge-" + NEOFORGE_VERSION + "-universal.jar",
                    launchTarget);
        }

        private static String installerVersionJson(
                String id,
                String universalCoordinate,
                String universalPath,
                String launchTarget) {
            return """
                    {
                      "id": "%s",
                      "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                      "libraries": [
                        {
                          "name": "cpw.mods:bootstraplauncher:2.0.0"
                        },
                        {
                          "name": "%s",
                          "path": "%s"
                        }
                      ],
                      "arguments": {
                        "jvm": [
                          "-DlibraryDirectory=${library_directory}"
                        ],
                        "game": [
                          "--launchTarget",
                          "%s"
                        ]
                      }
                    }
                    """.formatted(id, universalCoordinate, universalPath, launchTarget);
        }

        private static byte[] jarBytes() throws IOException {
            Path jar = Files.createTempFile("production-gradle-fixture", ".jar");
            try {
                try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
                }
                return Files.readAllBytes(jar);
            } finally {
                Files.deleteIfExists(jar);
            }
        }

        private static byte[] installerBytes(String versionJson) throws IOException {
            Path jar = Files.createTempFile("production-gradle-installer", ".jar");
            try {
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                    output.putNextEntry(new JarEntry("version.json"));
                    output.write(versionJson.getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
                return Files.readAllBytes(jar);
            } finally {
                Files.deleteIfExists(jar);
            }
        }

        private static String sha1(String value) {
            return sha1(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String sha1(byte[] value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-1 digest is not available", exception);
            }
        }

        @Override
        public void close() {
            server.stop((int) Duration.ZERO.toSeconds());
        }
    }
}
