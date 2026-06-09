package dev.vfyjxf.gradle.production;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.StreamSupport;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionModsFunctionalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectDir;

    @Test
    void isolatesModDependenciesByProductionRun() throws IOException {
        writeMultiProjectFixture();

        gradleRunner("printProductionClientLaunchSpec", "--stacktrace").build();
        gradleRunner("printProductionServerLaunchSpec", "--stacktrace").build();

        String clientJar = jarPath("clientOnly");
        String serverJar = jarPath("serverOnly");
        assertThat(modFiles("client")).contains(clientJar).doesNotContain(serverJar);
        assertThat(modFiles("server")).contains(serverJar).doesNotContain(clientJar);
    }

    @Test
    void printProductionLaunchSpecSupportsConfigurationCache() throws IOException {
        writeMultiProjectFixture();

        BuildResult result = gradleRunner(
                        "printProductionClientLaunchSpec", "--configuration-cache", "--stacktrace")
                .build();
        BuildResult reused = gradleRunner(
                        "printProductionClientLaunchSpec", "--configuration-cache", "--stacktrace")
                .build();

        assertThat(modFiles("client")).contains(jarPath("clientOnly"));
        assertThat(result.getOutput()).contains("Configuration cache entry stored");
        assertThat(reused.getOutput()).contains("Configuration cache entry reused");
    }

    @Test
    void launchSpecRegeneratesWhenModPathChangesWithSameContent() throws IOException {
        writeSingleProjectFixture("mods/first.jar");
        writeJar(projectDir.resolve("mods/first.jar"));
        writeJar(projectDir.resolve("mods/second.jar"));

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();
        assertThat(rootModFiles("client")).containsExactly(projectDir.resolve("mods/first.jar")
                .toRealPath()
                .toString());

        writeSingleProjectFixture("mods/second.jar");
        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client"))
                .containsExactly(projectDir.resolve("mods/second.jar").toRealPath().toString());
    }

    @Test
    void includeProjectDefaultsToCurrentProjectArtifact() throws IOException {
        writeSingleProjectFixtureWithoutExplicitMods("");

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client")).containsExactly(projectDir.resolve("build/libs/production-mod-path-test-1.0.0.jar")
                .toRealPath()
                .toString());
    }

    @Test
    void includeProjectDoesNotTreatRuntimeDependenciesAsMods() throws IOException {
        writeJar(projectDir.resolve("libs/not-a-mod.jar"));
        writeSingleProjectFixtureBuild(
                """
                dependencies {
                    implementation files("libs/not-a-mod.jar")
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

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client"))
                .containsExactly(projectDir.resolve("build/libs/production-mod-path-test-1.0.0.jar")
                        .toRealPath()
                        .toString());
    }

    @Test
    void autoDetectUsesDetectedProductionArtifactTaskForIncludedProjectMod() throws IOException {
        writeBuildSrcFabricLoomPlugin();
        writeAutoDetectRemapJarFixture();

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client")).containsExactly(projectDir.resolve(
                        "build/libs/production-mod-path-test-1.0.0-remapped.jar")
                .toRealPath()
                .toString());
    }

    @Test
    void addProjectDependencyCanSelectGradleProjectConfiguration() throws IOException {
        writeProjectConfigurationSelectionFixture();

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        String productionJar = projectDir.resolve("variantMod/build/libs/variantMod-1.0.0-production.jar")
                .toRealPath()
                .toString();
        String defaultJar = projectDir.resolve("variantMod/build/libs/variantMod-1.0.0.jar")
                .toString();
        assertThat(modFiles("client")).containsExactly(productionJar).doesNotContain(defaultJar);
    }

    @Test
    void addAcceptsTaskOutputProvider() throws IOException {
        writeSingleProjectFixtureBuild(
                """
                tasks.register("customProductionMod", Jar) {
                    archiveFileName = "custom-task-output.jar"
                    from sourceSets.main.output
                }

                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                            mods {
                                includeProject false
                                add tasks.named("customProductionMod")
                            }
                        }
                    }
                }
                """);

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client")).containsExactly(projectDir.resolve("build/libs/custom-task-output.jar")
                .toRealPath()
                .toString());
    }

    @Test
    void includeProjectCanDisableCurrentProjectArtifact() throws IOException {
        writeSingleProjectFixtureWithoutExplicitMods("mods { includeProject false }");

        gradleRunner("generateProductionClientLaunchSpec", "--stacktrace").build();

        assertThat(rootModFiles("client")).isEmpty();
    }

    @Test
    void modrinthDslResolvesRemoteModIntoLaunchSpec() throws IOException {
        byte[] jar = "sodium jar".getBytes(StandardCharsets.UTF_8);
        try (TestServer server = TestServer.start()) {
            server.bytes("/files/sodium.jar", jar);
            server.json("/project/sodium/version", """
                    [
                      {
                        "id": "version-sodium",
                        "project_id": "project-sodium",
                        "version_number": "mc1.21.1-0.6.0",
                        "game_versions": ["1.21.1"],
                        "loaders": ["fabric"],
                        "files": [
                          {
                            "filename": "sodium.jar",
                            "url": "%s/files/sodium.jar",
                            "primary": true,
                            "hashes": {
                              "sha512": "%s",
                              "sha1": "%s"
                            }
                          }
                        ]
                      }
                    ]
                    """.formatted(server.baseUrl(), sha512(jar), sha1(jar)));
            server.json("/project/project-sodium/dependencies", """
                    {
                      "projects": [],
                      "versions": [],
                      "dependencies": []
                    }
                    """);
            writeSingleProjectFixtureWithRunBody(
                    """
                                mods {
                                    includeProject false
                                    modrinth("sodium") {
                                        version = "mc1.21.1-0.6.0"
                                    }
                                }
                    """);

            gradleRunner(
                            "generateProductionClientLaunchSpec",
                            "-Pproduction.modrinthBaseUri=" + server.baseUrl(),
                            "--stacktrace")
                    .build();

            assertThat(rootModFiles("client"))
                    .singleElement()
                    .asString()
                    .endsWith("mods/modrinth/project-sodium/version-sodium/sodium.jar");
            assertThat(server.requestPaths())
                    .contains("/project/sodium/version", "/project/project-sodium/dependencies");
        }
    }

    @Test
    void modrinthVersionDslResolvesRemoteModIntoLaunchSpec() throws IOException {
        byte[] jar = "direct version jar".getBytes(StandardCharsets.UTF_8);
        try (TestServer server = TestServer.start()) {
            server.bytes("/files/direct.jar", jar);
            server.json("/version/version-direct", modrinthVersionJson(
                    server,
                    "version-direct",
                    "project-direct",
                    "1.0.0",
                    "/files/direct.jar",
                    "direct.jar",
                    sha512(jar),
                    sha1(jar)));
            server.json("/project/project-direct/dependencies", """
                    {
                      "projects": [],
                      "versions": [],
                      "dependencies": []
                    }
                    """);
            writeSingleProjectFixtureWithRunBody(
                    """
                                mods {
                                    includeProject false
                                    modrinthVersion("version-direct")
                                }
                    """);

            gradleRunner(
                            "generateProductionClientLaunchSpec",
                            "-Pproduction.modrinthBaseUri=" + server.baseUrl(),
                            "--stacktrace")
                    .build();

            assertThat(rootModFiles("client"))
                    .singleElement()
                    .asString()
                    .endsWith("mods/modrinth/project-direct/version-direct/direct.jar");
            assertThat(server.requestPaths())
                    .contains("/version/version-direct", "/project/project-direct/dependencies");
        }
    }

    @Test
    void curseForgeDslResolvesRemoteModIntoLaunchSpec() throws IOException {
        byte[] jar = "jei jar".getBytes(StandardCharsets.UTF_8);
        try (TestServer server = TestServer.start()) {
            server.bytes("/files/jei.jar", jar);
            server.json("/v1/mods/238222/files/5746926", curseForgeFileJson(
                    server,
                    238222,
                    5746926,
                    "jei.jar",
                    "/files/jei.jar",
                    sha1(jar)));
            writeSingleProjectFixtureWithRunBody(
                    """
                                mods {
                                    includeProject false
                                    curseforge("jei") {
                                        projectId = 238222
                                        fileId = 5746926
                                    }
                                }
                    """);

            gradleRunner(
                            "generateProductionClientLaunchSpec",
                            "-Pproduction.curseforgeBaseUri=" + server.baseUrl(),
                            "-Pproduction.curseforgeApiKey=test-key",
                            "--stacktrace")
                    .build();

            assertThat(rootModFiles("client"))
                    .singleElement()
                    .asString()
                    .endsWith("mods/curseforge/238222/5746926/jei.jar");
            assertThat(server.requestPaths()).contains("/v1/mods/238222/files/5746926");
            assertThat(server.header("/v1/mods/238222/files/5746926", "x-api-key"))
                    .containsExactly("test-key");
        }
    }

    private void writeMultiProjectFixture() throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "production-mods-test"
                include "app", "clientOnly", "serverOnly"
                """);
        Files.writeString(projectDir.resolve("build.gradle"), "");
        writeBuildFile("clientOnly", "plugins { id \"java\" }\nversion = \"1.0.0\"\n");
        writeBuildFile("serverOnly", "plugins { id \"java\" }\nversion = \"1.0.0\"\n");
        writeBuildFile(
                "app",
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
                            mods {
                                add project(":clientOnly")
                            }
                        }
                        server {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                            eula true
                            mods {
                                add project(":serverOnly")
                            }
                        }
                    }
                }
                """);
    }

    private void writeSingleProjectFixture(String modPath) throws IOException {
        writeSingleProjectFixtureWithRunBody(
                """
                            mods {
                                includeProject false
                                add files("%s")
                            }
                """
                        .formatted(modPath));
    }

    private void writeSingleProjectFixtureWithoutExplicitMods(String runBody) throws IOException {
        writeSingleProjectFixtureWithRunBody(runBody);
    }

    private void writeSingleProjectFixtureWithRunBody(String runBody) throws IOException {
        writeSingleProjectFixtureBuild(
                """
                production {
                    runs {
                        client {
                            minecraftVersion = "1.21.1"
                            loader = "fabric"
                            loaderVersion = "0.16.10"
                %s
                        }
                    }
                }
                """
                        .formatted(runBody));
    }

    private void writeSingleProjectFixtureBuild(String body) throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "production-mod-path-test"
                """);
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "dev.vfyjxf.gradle.production"
                }

                version = "1.0.0"

                %s
                """
                        .formatted(body));
    }

    private void writeProjectConfigurationSelectionFixture() throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "production-project-configuration-test"
                include "app", "variantMod"
                """);
        Files.writeString(projectDir.resolve("build.gradle"), "");
        writeBuildFile(
                "variantMod",
                """
                plugins {
                    id "java"
                }

                version = "1.0.0"

                tasks.register("productionJar", Jar) {
                    archiveClassifier = "production"
                    from sourceSets.main.output
                }

                configurations {
                    productionElements {
                        canBeConsumed = true
                        canBeResolved = false
                        visible = false
                        attributes {
                            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
                            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, LibraryElements.JAR))
                        }
                    }
                }

                artifacts {
                    productionElements tasks.named("productionJar")
                }
                """);
        writeBuildFile(
                "app",
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
                            mods {
                                includeProject false
                                add dependencies.project(path: ":variantMod", configuration: "productionElements")
                            }
                        }
                    }
                }
                """);
    }

    private void writeAutoDetectRemapJarFixture() throws IOException {
        Files.writeString(
                projectDir.resolve("settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "production-mod-path-test"
                """);
        Files.writeString(
                projectDir.resolve("build.gradle"),
                """
                plugins {
                    id "java"
                    id "fabric-loom"
                    id "dev.vfyjxf.gradle.production"
                }

                version = "1.0.0"

                tasks.register("remapJar", Jar) {
                    archiveClassifier = "remapped"
                    from sourceSets.main.output
                }

                production {
                    runs {
                        client {
                        }
                    }
                }
                """);
    }

    private void writeBuildSrcFabricLoomPlugin() throws IOException {
        Path buildSrc = projectDir.resolve("buildSrc");
        Files.createDirectories(buildSrc.resolve("src/main/groovy"));
        Files.writeString(
                buildSrc.resolve("settings.gradle"),
                "rootProject.name = 'production-mods-build-src'\n");
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
    }

    private void writeBuildFile(String projectName, String buildScript) throws IOException {
        Path projectPath = projectDir.resolve(projectName);
        Files.createDirectories(projectPath);
        Files.writeString(projectPath.resolve("build.gradle"), buildScript);
    }

    private Iterable<String> modFiles(String runName) throws IOException {
        Path specPath = projectDir.resolve("app/build/production-gradle/specs/" + runName + "/launch-spec.json");
        return modFiles(specPath);
    }

    private Iterable<String> rootModFiles(String runName) throws IOException {
        Path specPath = projectDir.resolve("build/production-gradle/specs/" + runName + "/launch-spec.json");
        return modFiles(specPath);
    }

    private Iterable<String> modFiles(Path specPath) throws IOException {
        assertThat(specPath).isRegularFile();
        JsonNode mods = JSON.readTree(specPath.toFile()).path("mods");
        return StreamSupport.stream(mods.spliterator(), false)
                .map(mod -> mod.path("file").asText())
                .toList();
    }

    private String jarPath(String projectName) throws IOException {
        return projectDir.resolve(projectName + "/build/libs/" + projectName + "-1.0.0.jar")
                .toRealPath()
                .toString();
    }

    private void writeJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry entry = new JarEntry("mod.txt");
            entry.setTime(0L);
            jar.putNextEntry(entry);
            jar.write("same content".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static String sha1(byte[] bytes) {
        return digest(bytes, "SHA-1");
    }

    private static String sha512(byte[] bytes) {
        return digest(bytes, "SHA-512");
    }

    private static String digest(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String modrinthVersionJson(
            TestServer server,
            String versionId,
            String projectId,
            String versionNumber,
            String filePath,
            String filename,
            String sha512,
            String sha1) {
        return """
                {
                  "id": "%s",
                  "project_id": "%s",
                  "version_number": "%s",
                  "game_versions": ["1.21.1"],
                  "loaders": ["fabric"],
                  "files": [
                    {
                      "filename": "%s",
                      "url": "%s%s",
                      "primary": true,
                      "hashes": {
                        "sha512": "%s",
                        "sha1": "%s"
                      }
                    }
                  ]
                }
                """.formatted(versionId, projectId, versionNumber, filename, server.baseUrl(), filePath, sha512, sha1);
    }

    private static String curseForgeFileJson(
            TestServer server,
            int projectId,
            int fileId,
            String fileName,
            String downloadPath,
            String sha1) {
        return """
                {
                  "data": {
                    "id": %d,
                    "modId": %d,
                    "displayName": "%s",
                    "fileName": "%s",
                    "downloadUrl": "%s%s",
                    "gameVersions": ["1.21.1", "Fabric"],
                    "dependencies": [],
                    "hashes": [
                      {"value": "%s", "algo": 1}
                    ]
                  }
                }
                """.formatted(fileId, projectId, fileName, fileName, server.baseUrl(), downloadPath, sha1);
    }

    private GradleRunner gradleRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Response> responses = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> requestHeaders = new LinkedHashMap<>();
        private final List<String> requestPaths = new java.util.ArrayList<>();

        private TestServer(HttpServer server) {
            this.server = server;
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        static TestServer start() {
            try {
                return new TestServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> requestPaths() {
            return List.copyOf(requestPaths);
        }

        List<String> header(String path, String name) {
            return requestHeaders.getOrDefault(path, Map.of()).getOrDefault(name, List.of());
        }

        void json(String path, String body) {
            responses.put(path, new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8)));
        }

        void bytes(String path, byte[] body) {
            responses.put(path, new Response(200, "application/java-archive", body));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            requestPaths.add(path);
            requestHeaders.put(path, normalizedHeaders(exchange));
            Response response = responses.get(path);
            if (response == null) {
                response = new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), response.body().length);
            exchange.getResponseBody().write(response.body());
            exchange.close();
        }

        private Map<String, List<String>> normalizedHeaders(HttpExchange exchange) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values)));
            return Map.copyOf(headers);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Response(int status, String contentType, byte[] body) {
        }
    }
}
