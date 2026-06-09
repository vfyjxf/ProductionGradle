package dev.vfyjxf.gradle.mods;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModrinthProviderTest {
    private final TestServer server = new TestServer();

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void slugResolvesToVersionMatchingMinecraftAndLoader() throws Exception {
        byte[] jar = "sodium jar".getBytes(StandardCharsets.UTF_8);
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
                  },
                  {
                    "id": "version-old",
                    "project_id": "project-sodium",
                    "version_number": "old",
                    "game_versions": ["1.20.1"],
                    "loaders": ["fabric"],
                    "files": []
                  }
                ]
                """.formatted(server.baseUrl(), sha512(jar), sha1(jar)));
        server.json("/project/project-sodium/dependencies", emptyDependenciesJson());

        ModProviderResult result = new ModrinthProvider(server.baseUri())
                .resolve(context(false), ModRequest.modrinthProject("sodium"));

        assertThat(result.mods()).singleElement().satisfies(mod -> {
            assertThat(mod.source()).isEqualTo("modrinth");
            assertThat(mod.id()).isEqualTo("project-sodium");
            assertThat(mod.version()).isEqualTo("mc1.21.1-0.6.0");
            assertThat(mod.file()).hasFileName("sodium.jar");
            assertThat(mod.metadata())
                    .containsEntry("versionId", "version-sodium")
                    .containsEntry("projectId", "project-sodium");
        });
        assertThat(Files.readString(result.mods().getFirst().file())).isEqualTo("sodium jar");
    }

    @Test
    void versionIdResolvesDirectly() throws Exception {
        byte[] jar = "direct version jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/direct.jar", jar);
        server.json("/version/version-direct", modrinthVersionJson(
                "version-direct",
                "project-direct",
                "1.0.0",
                "/files/direct.jar",
                "direct.jar",
                sha512(jar),
                sha1(jar)));
        server.json("/project/project-direct/dependencies", emptyDependenciesJson());

        ModProviderResult result = new ModrinthProvider(server.baseUri())
                .resolve(context(false), ModRequest.modrinthVersion("version-direct"));

        assertThat(result.mods()).singleElement().satisfies(mod -> {
            assertThat(mod.id()).isEqualTo("project-direct");
            assertThat(mod.version()).isEqualTo("1.0.0");
            assertThat(mod.file()).hasFileName("direct.jar");
        });
    }

    @Test
    void offlineModeFailsBeforeRequestingMetadata() {
        assertThatThrownBy(() -> new ModrinthProvider(server.baseUri())
                .resolve(context(false, true), ModRequest.modrinthProject("sodium")))
                .isInstanceOf(ModResolutionException.class)
                .hasMessageContaining("offline");

        assertThat(server.requestPaths()).isEmpty();
    }

    @Test
    void requiredDependenciesAreRecursivelyAdded() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] dependencyJar = "dependency jar".getBytes(StandardCharsets.UTF_8);
        byte[] transitiveJar = "transitive jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/dependency.jar", dependencyJar);
        server.bytes("/files/transitive.jar", transitiveJar);
        server.json("/version/root-version", modrinthVersionJson(
                "root-version", "root-project", "1.0.0", "/files/root.jar", "root.jar", sha512(rootJar), sha1(rootJar)));
        server.json("/project/root-project/dependencies", """
                {
                  "projects": [],
                  "versions": [
                    %s
                  ],
                  "dependencies": [
                    {"version_id": "dependency-version", "dependency_type": "required"}
                  ]
                }
                """.formatted(modrinthVersionJson(
                "dependency-version", "dependency-project", "2.0.0", "/files/dependency.jar", "dependency.jar",
                sha512(dependencyJar), sha1(dependencyJar))));
        server.json("/project/dependency-project/dependencies", """
                {
                  "projects": [],
                  "versions": [
                    %s
                  ],
                  "dependencies": [
                    {"version_id": "transitive-version", "dependency_type": "required"}
                  ]
                }
                """.formatted(modrinthVersionJson(
                "transitive-version", "transitive-project", "3.0.0", "/files/transitive.jar", "transitive.jar",
                sha512(transitiveJar), sha1(transitiveJar))));
        server.json("/project/transitive-project/dependencies", """
                {
                  "projects": [],
                  "versions": [],
                  "dependencies": []
                }
                """);

        ModProviderResult result = new ModrinthProvider(server.baseUri())
                .resolve(context(false), ModRequest.modrinthVersion("root-version"));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("root-project", "dependency-project", "transitive-project");
    }

    @Test
    void optionalDependenciesAreSkippedWhenDisabled() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] optionalJar = "optional jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/optional.jar", optionalJar);
        server.json("/version/root-version", modrinthVersionJson(
                "root-version", "root-project", "1.0.0", "/files/root.jar", "root.jar", sha512(rootJar), sha1(rootJar)));
        server.json("/project/root-project/dependencies", """
                {
                  "projects": [],
                  "versions": [
                    %s
                  ],
                  "dependencies": [
                    {"version_id": "optional-version", "dependency_type": "optional"}
                  ]
                }
                """.formatted(modrinthVersionJson(
                "optional-version", "optional-project", "2.0.0", "/files/optional.jar", "optional.jar",
                sha512(optionalJar), sha1(optionalJar))));

        ModProviderResult result = new ModrinthProvider(server.baseUri())
                .resolve(context(false), ModRequest.modrinthVersion("root-version"));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("root-project");
        assertThat(server.requestPaths()).doesNotContain("/files/optional.jar");
    }

    @Test
    void optionalDependenciesAreIncludedWhenEnabled() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] optionalJar = "optional jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/optional.jar", optionalJar);
        server.json("/version/root-version", modrinthVersionJson(
                "root-version", "root-project", "1.0.0", "/files/root.jar", "root.jar", sha512(rootJar), sha1(rootJar)));
        server.json("/project/root-project/dependencies", """
                {
                  "projects": [],
                  "versions": [
                    %s
                  ],
                  "dependencies": [
                    {"version_id": "optional-version", "dependency_type": "optional"}
                  ]
                }
                """.formatted(modrinthVersionJson(
                "optional-version", "optional-project", "2.0.0", "/files/optional.jar", "optional.jar",
                sha512(optionalJar), sha1(optionalJar))));
        server.json("/project/optional-project/dependencies", """
                {
                  "projects": [],
                  "versions": [],
                  "dependencies": []
                }
                """);

        ModProviderResult result = new ModrinthProvider(server.baseUri())
                .resolve(context(true), ModRequest.modrinthVersion("root-version"));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("root-project", "optional-project");
    }

    @Test
    void hashMismatchFails() {
        byte[] jar = "corrupt jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/corrupt.jar", jar);
        server.json("/version/corrupt-version", modrinthVersionJson(
                "corrupt-version", "corrupt-project", "1.0.0", "/files/corrupt.jar", "corrupt.jar",
                "0".repeat(128), sha1(jar)));

        assertThatThrownBy(() -> new ModrinthProvider(server.baseUri())
                .resolve(context(false), ModRequest.modrinthVersion("corrupt-version")))
                .isInstanceOf(ModResolutionException.class)
                .hasMessageContaining("checksum");
    }

    private ModProviderContext context(boolean includeOptionalDependencies) {
        return context(includeOptionalDependencies, false);
    }

    private ModProviderContext context(boolean includeOptionalDependencies, boolean offline) {
        return ModProviderContext.builder()
                .minecraftVersion("1.21.1")
                .loader("fabric")
                .cacheLayout(CacheLayout.under(tempDir))
                .includeRequiredDependencies(true)
                .includeOptionalDependencies(includeOptionalDependencies)
                .offline(offline)
                .build();
    }

    private String modrinthVersionJson(
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

    private String emptyDependenciesJson() {
        return """
                {
                  "projects": [],
                  "versions": [],
                  "dependencies": []
                }
                """;
    }

    private static String sha1(byte[] bytes) {
        return digest(bytes, "SHA-1");
    }

    private static String sha512(byte[] bytes) {
        return digest(bytes, "SHA-512");
    }

    private static String digest(byte[] bytes, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Response> responses = new LinkedHashMap<>();
        private final List<String> requestPaths = new java.util.ArrayList<>();

        private TestServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        private URI baseUri() {
            return URI.create(baseUrl());
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<String> requestPaths() {
            return List.copyOf(requestPaths);
        }

        private void json(String path, String body) {
            responses.put(path, new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8)));
        }

        private void bytes(String path, byte[] body) {
            responses.put(path, new Response(200, "application/java-archive", body));
        }

        private void handle(HttpExchange exchange) throws IOException {
            requestPaths.add(exchange.getRequestURI().getPath());
            Response response = responses.get(exchange.getRequestURI().getPath());
            if (response == null) {
                response = new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), response.body().length);
            exchange.getResponseBody().write(response.body());
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Response(int status, String contentType, byte[] body) {
        }
    }
}
