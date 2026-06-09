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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurseForgeProviderTest {
    private final TestServer server = new TestServer();

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void missingApiKeyFailsWithDocumentedMessage() {
        ModProviderContext context = contextBuilder(false)
                .curseForgeApiKey(null)
                .build();

        assertThatThrownBy(() -> new CurseForgeProvider(server.baseUri())
                .resolve(context, ModRequest.curseForgeFile(238222, 5746926)))
                .isInstanceOf(ModResolutionException.class)
                .hasMessageContaining("CURSEFORGE_API_KEY");
    }

    @Test
    void projectIdAndFileIdResolveMetadataWhenApiKeyIsPresent() throws Exception {
        byte[] jar = "jei jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/jei.jar", jar);
        server.json("/v1/mods/238222/files/5746926", curseForgeFileJson(
                238222,
                5746926,
                "jei.jar",
                "/files/jei.jar",
                List.of(),
                sha1(jar)));

        ModProviderResult result = new CurseForgeProvider(server.baseUri())
                .resolve(contextBuilder(false).curseForgeApiKey("test-key").build(), ModRequest.curseForgeFile(238222, 5746926));

        assertThat(result.mods()).singleElement().satisfies(mod -> {
            assertThat(mod.source()).isEqualTo("curseforge");
            assertThat(mod.id()).isEqualTo("238222");
            assertThat(mod.version()).isEqualTo("5746926");
            assertThat(mod.file()).hasFileName("jei.jar");
            assertThat(mod.metadata())
                    .containsEntry("projectId", "238222")
                    .containsEntry("fileId", "5746926");
        });
        assertThat(Files.readString(result.mods().getFirst().file())).isEqualTo("jei jar");
        assertThat(server.header("/v1/mods/238222/files/5746926", "x-api-key")).containsExactly("test-key");
    }

    @Test
    void offlineModeFailsBeforeRequestingMetadata() {
        ModProviderContext context = contextBuilder(false)
                .curseForgeApiKey("test-key")
                .offline(true)
                .build();

        assertThatThrownBy(() -> new CurseForgeProvider(server.baseUri())
                .resolve(context, ModRequest.curseForgeFile(238222, 5746926)))
                .isInstanceOf(ModResolutionException.class)
                .hasMessageContaining("offline");

        assertThat(server.requestPaths()).isEmpty();
    }

    @Test
    void requiredRelationTypeThreeResolvesCompatibleDependencyFile() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] dependencyJar = "dependency jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/dependency.jar", dependencyJar);
        server.json("/v1/mods/10/files/100", curseForgeFileJson(
                10,
                100,
                "root.jar",
                "/files/root.jar",
                List.of(new Dependency(20, 3)),
                sha1(rootJar)));
        server.json("/v1/mods/20/files", curseForgeFilesJson(
                curseForgeFileObjectJson(
                        20,
                        200,
                        "old-dependency.jar",
                        "/files/dependency.jar",
                        List.of(),
                        sha1(dependencyJar),
                        List.of("1.20.1", "Fabric")),
                curseForgeFileObjectJson(
                        20,
                        201,
                        "dependency.jar",
                        "/files/dependency.jar",
                        List.of(),
                        sha1(dependencyJar))));

        ModProviderResult result = new CurseForgeProvider(server.baseUri())
                .resolve(contextBuilder(false).curseForgeApiKey("test-key").build(), ModRequest.curseForgeFile(10, 100));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("10", "20");
        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::version)
                .containsExactly("100", "201");
        assertThat(server.requestPaths()).contains("/v1/mods/20/files");
        assertThat(server.requestUris()).contains("/v1/mods/20/files?gameVersion=1.21.1&modLoaderType=4&pageSize=50");
        assertThat(server.requestPaths()).doesNotContain("/v1/mods/20/files/0");
    }

    @Test
    void optionalRelationTypeTwoIsSkippedWhenDisabled() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] optionalJar = "optional jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/optional.jar", optionalJar);
        server.json("/v1/mods/10/files/100", curseForgeFileJson(
                10,
                100,
                "root.jar",
                "/files/root.jar",
                List.of(new Dependency(30, 2)),
                sha1(rootJar)));
        server.json("/v1/mods/30/files", curseForgeFilesJson(curseForgeFileObjectJson(
                30,
                300,
                "optional.jar",
                "/files/optional.jar",
                List.of(),
                sha1(optionalJar))));

        ModProviderResult result = new CurseForgeProvider(server.baseUri())
                .resolve(contextBuilder(false).curseForgeApiKey("test-key").build(), ModRequest.curseForgeFile(10, 100));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("10");
        assertThat(server.requestPaths()).doesNotContain("/v1/mods/30/files");
    }

    @Test
    void optionalRelationTypeTwoIsIncludedWhenEnabled() throws Exception {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        byte[] optionalJar = "optional jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.bytes("/files/optional.jar", optionalJar);
        server.json("/v1/mods/10/files/100", curseForgeFileJson(
                10,
                100,
                "root.jar",
                "/files/root.jar",
                List.of(new Dependency(30, 2)),
                sha1(rootJar)));
        server.json("/v1/mods/30/files", curseForgeFilesJson(curseForgeFileObjectJson(
                30,
                300,
                "optional.jar",
                "/files/optional.jar",
                List.of(),
                sha1(optionalJar))));

        ModProviderResult result = new CurseForgeProvider(server.baseUri())
                .resolve(contextBuilder(true).curseForgeApiKey("test-key").build(), ModRequest.curseForgeFile(10, 100));

        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::id)
                .containsExactly("10", "30");
        assertThat(result.mods())
                .extracting(ModProviderResult.ModFile::version)
                .containsExactly("100", "300");
    }

    @Test
    void incompatibleRelationTypeFiveFails() {
        byte[] rootJar = "root jar".getBytes(StandardCharsets.UTF_8);
        server.bytes("/files/root.jar", rootJar);
        server.json("/v1/mods/10/files/100", curseForgeFileJson(
                10,
                100,
                "root.jar",
                "/files/root.jar",
                List.of(new Dependency(40, 5)),
                sha1(rootJar)));

        assertThatThrownBy(() -> new CurseForgeProvider(server.baseUri())
                .resolve(contextBuilder(false).curseForgeApiKey("test-key").build(), ModRequest.curseForgeFile(10, 100)))
                .isInstanceOf(ModResolutionException.class)
                .hasMessageContaining("incompatible");
    }

    private ModProviderContext.Builder contextBuilder(boolean includeOptionalDependencies) {
        return ModProviderContext.builder()
                .minecraftVersion("1.21.1")
                .loader("fabric")
                .cacheLayout(CacheLayout.under(tempDir))
                .includeRequiredDependencies(true)
                .includeOptionalDependencies(includeOptionalDependencies)
                .offline(false);
    }

    private String curseForgeFileJson(
            int projectId,
            int fileId,
            String fileName,
            String downloadPath,
            List<Dependency> dependencies,
            String sha1) {
        return """
                {
                  "data": %s
                }
                """.formatted(curseForgeFileObjectJson(projectId, fileId, fileName, downloadPath, dependencies, sha1));
    }

    private String curseForgeFilesJson(String... fileObjects) {
        return """
                {
                  "data": [%s],
                  "pagination": {
                    "index": 0,
                    "pageSize": 50,
                    "resultCount": %d,
                    "totalCount": %d
                  }
                }
                """.formatted(String.join(",", fileObjects), fileObjects.length, fileObjects.length);
    }

    private String curseForgeFileObjectJson(
            int projectId,
            int fileId,
            String fileName,
            String downloadPath,
            List<Dependency> dependencies,
            String sha1) {
        return curseForgeFileObjectJson(
                projectId,
                fileId,
                fileName,
                downloadPath,
                dependencies,
                sha1,
                List.of("1.21.1", "Fabric"));
    }

    private String curseForgeFileObjectJson(
            int projectId,
            int fileId,
            String fileName,
            String downloadPath,
            List<Dependency> dependencies,
            String sha1,
            List<String> gameVersions) {
        return """
                {
                  "id": %d,
                  "modId": %d,
                  "displayName": "%s",
                  "fileName": "%s",
                  "downloadUrl": "%s%s",
                  "gameVersions": [%s],
                  "dependencies": [%s],
                  "hashes": [
                    {"value": "%s", "algo": 1}
                  ]
                }
                """.formatted(
                fileId,
                projectId,
                fileName,
                fileName,
                server.baseUrl(),
                downloadPath,
                stringArrayJson(gameVersions),
                dependencyJson(dependencies),
                sha1);
    }

    private String stringArrayJson(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String dependencyJson(List<Dependency> dependencies) {
        return dependencies.stream()
                .map(dependency -> """
                        {"modId": %d, "relationType": %d}""".formatted(dependency.modId(), dependency.relationType()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String sha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Dependency(int modId, int relationType) {
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, Response> responses = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> requestHeaders = new LinkedHashMap<>();
        private final List<String> requestPaths = new java.util.ArrayList<>();
        private final List<String> requestUris = new java.util.ArrayList<>();

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

        private List<String> requestUris() {
            return List.copyOf(requestUris);
        }

        private List<String> header(String path, String name) {
            return requestHeaders.getOrDefault(path, Map.of()).getOrDefault(name, List.of());
        }

        private void json(String path, String body) {
            responses.put(path, new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8)));
        }

        private void bytes(String path, byte[] body) {
            responses.put(path, new Response(200, "application/java-archive", body));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            requestPaths.add(path);
            requestUris.add(exchange.getRequestURI().toString());
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
                    headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
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
