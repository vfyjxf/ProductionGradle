package dev.vfyjxf.gradle.launcher.core.loader.vanilla;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchAuth;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchJava;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchPaths;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSettings;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidator;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.minecraft.LibraryRuleEvaluator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VanillaResolverTest {
    private static final String VERSION = "1.21.1";

    @TempDir
    Path tempDir;

    @Test
    void preparesClientFromCachedVersionMetadataAndInterpolatesArguments() throws Exception {
        LaunchSpec spec = validSpec("client");
        Path versionDirectory = versionDirectory(spec);
        Path library = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("org/example/alpha/1.0/alpha-1.0.jar"));
        Path clientJar = writeFile(versionDirectory.resolve(VERSION + ".jar"));
        writeVersionJson(versionDirectory, clientVersionJson());

        PreparedLaunch launch = resolver().prepare(spec);

        assertThat(launch.classpath()).containsExactly(library, clientJar);
        assertThat(launch.mainClass()).isEqualTo("net.minecraft.client.main.Main");
        assertThat(launch.jvmArgs()).containsExactly(
                "-Djava.library.path=" + spec.paths().nativesDir(),
                "-cp",
                library + File.pathSeparator + clientJar,
                "-DcustomCache=" + spec.paths().cacheDir());
        assertThat(launch.gameArgs()).containsExactly(
                "--username",
                "DevPlayer",
                "--version",
                VERSION,
                "--gameDir",
                spec.paths().workingDir().toString(),
                "--assetsDir",
                spec.paths().assetsDir().toString(),
                "--assetIndex",
                "17",
                "--uuid",
                "74ffd48866e1390eb3a468445a2d0ab8",
                "--accessToken",
                "offline",
                "--userType",
                "offline",
                "--versionType",
                "release",
                "--assetPath",
                spec.paths().assetsDir().toString(),
                "--customGameDir=" + spec.paths().workingDir());
    }

    @Test
    void preparesServerFromCachedServerJarManifestAndIgnoresClientMetadataArguments() throws Exception {
        LaunchSpec spec = validSpec("server");
        Path versionDirectory = versionDirectory(spec);
        writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("org/example/alpha/1.0/alpha-1.0.jar"));
        Path serverJar = writeJar(versionDirectory.resolve(VERSION + "-server.jar"), "com.example.ServerMain");
        writeVersionJson(versionDirectory, officialServerVersionJson());

        PreparedLaunch launch = resolver().prepare(spec);

        assertThat(launch.mainClass()).isEqualTo("com.example.ServerMain");
        assertThat(launch.classpath()).containsExactly(serverJar);
        assertThat(launch.jvmArgs()).containsExactly("-DcustomCache=" + spec.paths().cacheDir());
        assertThat(launch.gameArgs()).containsExactly("--customGameDir=" + spec.paths().workingDir());
        assertThat(launch.gameArgs()).doesNotContain("--username", "--assetsDir", "--accessToken");
    }

    @Test
    void usesLaunchSpecMainClassOverrideBeforeServerJarManifest() throws Exception {
        LaunchSpec spec = validSpec("server", "com.example.OverrideMain");
        Path versionDirectory = versionDirectory(spec);
        writeJar(versionDirectory.resolve(VERSION + "-server.jar"), "com.example.ServerMain");
        writeVersionJson(versionDirectory, serverVersionJsonWithClientMain());

        PreparedLaunch launch = resolver().prepare(spec);

        assertThat(launch.mainClass()).isEqualTo("com.example.OverrideMain");
    }

    @Test
    void fallsBackToBundlerMainWhenServerJarHasNoManifestMainClass() throws Exception {
        LaunchSpec spec = validSpec("server");
        Path versionDirectory = versionDirectory(spec);
        writeJar(versionDirectory.resolve(VERSION + "-server.jar"), null);
        writeVersionJson(versionDirectory, serverVersionJsonWithClientMain());

        PreparedLaunch launch = resolver().prepare(spec);

        assertThat(launch.mainClass()).isEqualTo("net.minecraft.bundler.Main");
    }

    @Test
    void downloadsVersionMetadataAndServerJarWhenOnline() throws Exception {
        byte[] serverJar = jarBytes("com.example.DownloadedServerMain");

        try (TestServer server = TestServer.start()) {
            server.response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "type": "release",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson(server.baseUri(), sha1(serverJar)))));
            server.response("/version.json", versionJson(server.baseUri(), sha1(serverJar)));
            server.response("/server.jar", serverJar);

            LaunchSpec spec = validSpec("server", null, false);
            PreparedLaunch launch = resolver(server.baseUri().resolve("/version-manifest.json"), assetBaseUri(server))
                    .prepare(spec);

            Path downloadedServer = versionDirectory(spec).resolve(VERSION + "-server.jar");
            assertThat(downloadedServer).isRegularFile();
            assertThat(launch.classpath()).containsExactly(downloadedServer);
            assertThat(launch.mainClass()).isEqualTo("com.example.DownloadedServerMain");
        }
    }

    @Test
    void downloadsClientJarLibrariesAssetIndexAssetsAndNativesWhenOnline() throws Exception {
        byte[] clientJar = "client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] libraryJar = "library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nativeJar = nativeJarBytes();
        byte[] asset = "asset".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String assetIndexJson = """
                {
                  "objects": {
                    "minecraft/sounds/test.ogg": {
                      "hash": "%s",
                      "size": 5
                    }
                  }
                }
                """.formatted(sha1(asset));

        try (TestServer server = TestServer.start()) {
            String versionJson = clientDownloadVersionJson(
                    server.baseUri(),
                    sha1(clientJar),
                    sha1(libraryJar),
                    sha1(nativeJar),
                    sha1(assetIndexJson));
            server.response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "type": "release",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson)));
            server.response("/version.json", versionJson);
            server.response("/client.jar", clientJar);
            server.response("/library.jar", libraryJar);
            server.response("/native.jar", nativeJar);
            server.response("/indexes/17.json", assetIndexJson);
            server.response("/assets/" + sha1(asset).substring(0, 2) + "/" + sha1(asset), asset);

            LaunchSpec spec = validSpec("client", null, false);
            PreparedLaunch launch = resolver(server.baseUri().resolve("/version-manifest.json"), assetBaseUri(server))
                    .prepare(spec);

            Path clientPath = versionDirectory(spec).resolve(VERSION + ".jar");
            Path libraryPath = spec.paths().cacheDir().resolve("libraries/org/example/alpha/1.0/alpha-1.0.jar");
            Path nativePath = spec.paths().cacheDir()
                    .resolve("libraries/org/example/native/1.0/native-1.0-" + nativeClassifier() + ".jar");
            Path assetIndexPath = spec.paths().cacheDir().resolve("assets/indexes/17.json");
            Path assetPath = spec.paths().cacheDir().resolve("assets/objects")
                    .resolve(sha1(asset).substring(0, 2))
                    .resolve(sha1(asset));

            assertThat(clientPath).isRegularFile();
            assertThat(libraryPath).isRegularFile();
            assertThat(nativePath).isRegularFile();
            assertThat(assetIndexPath).isRegularFile();
            assertThat(assetPath).isRegularFile();
            assertThat(spec.paths().nativesDir().resolve("native.bin")).isRegularFile();
            assertThat(spec.paths().nativesDir().resolve("META-INF/ignored.txt")).doesNotExist();
            assertThat(launch.classpath()).containsExactly(libraryPath, nativePath, clientPath);
        }
    }

    @Test
    void usesResolutionHintsForManifestAndAssetDownloads() throws Exception {
        byte[] clientJar = "client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] libraryJar = "library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nativeJar = nativeJarBytes();
        byte[] asset = "asset".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String assetIndexJson = """
                {
                  "objects": {
                    "minecraft/sounds/test.ogg": {
                      "hash": "%s",
                      "size": 5
                    }
                  }
                }
                """.formatted(sha1(asset));

        try (TestServer server = TestServer.start()) {
            String versionJson = clientDownloadVersionJson(
                    server.baseUri(),
                    sha1(clientJar),
                    sha1(libraryJar),
                    sha1(nativeJar),
                    sha1(assetIndexJson));
            server.response("/hint/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "type": "release",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson)));
            server.response("/version.json", versionJson);
            server.response("/client.jar", clientJar);
            server.response("/library.jar", libraryJar);
            server.response("/native.jar", nativeJar);
            server.response("/indexes/17.json", assetIndexJson);
            server.response("/hint/assets/" + sha1(asset).substring(0, 2) + "/" + sha1(asset), asset);

            LaunchSpec spec = validSpec(
                    "client",
                    null,
                    false,
                    Map.of(
                            "minecraftVersionManifestUri",
                            server.baseUri().resolve("/hint/version-manifest.json").toString(),
                            "minecraftAssetBaseUri",
                            server.baseUri().resolve("/hint/assets/").toString()));
            PreparedLaunch launch = resolver(
                            URI.create("http://127.0.0.1:1/default-version-manifest.json"),
                            URI.create("http://127.0.0.1:1/default-assets/"))
                    .prepare(spec);

            Path clientPath = versionDirectory(spec).resolve(VERSION + ".jar");
            Path assetPath = spec.paths().cacheDir().resolve("assets/objects")
                    .resolve(sha1(asset).substring(0, 2))
                    .resolve(sha1(asset));

            assertThat(clientPath).isRegularFile();
            assertThat(assetPath).isRegularFile();
            assertThat(launch.classpath()).contains(clientPath);
        }
    }

    @Test
    void downloadsAssetsWithProgressAndParallelRequests() throws Exception {
        byte[] clientJar = "client".getBytes(StandardCharsets.UTF_8);
        List<byte[]> assets = List.of(
                "asset-one".getBytes(StandardCharsets.UTF_8),
                "asset-two".getBytes(StandardCharsets.UTF_8),
                "asset-three".getBytes(StandardCharsets.UTF_8),
                "asset-four".getBytes(StandardCharsets.UTF_8));
        String assetIndexJson = assetIndexJson(assets);
        CountDownLatch firstTwoAssetRequests = new CountDownLatch(2);
        CountDownLatch releaseAssets = new CountDownLatch(1);

        try (TestServer server = TestServer.start()) {
            String versionJson = clientAssetsOnlyVersionJson(
                    server.baseUri(),
                    sha1(clientJar),
                    sha1(assetIndexJson));
            server.response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "type": "release",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson)));
            server.response("/version.json", versionJson);
            server.response("/client.jar", clientJar);
            server.response("/indexes/17.json", assetIndexJson);
            for (byte[] asset : assets) {
                String hash = sha1(asset);
                server.blockingResponse(
                        "/assets/" + hash.substring(0, 2) + "/" + hash,
                        asset,
                        firstTwoAssetRequests,
                        releaseAssets);
            }

            LaunchSpec spec = validSpec("client", null, false);
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return captureStandardOut(() -> resolver(
                                    server.baseUri().resolve("/version-manifest.json"),
                                    assetBaseUri(server))
                            .prepare(spec));
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });

            boolean parallelAssetRequests = firstTwoAssetRequests.await(2, TimeUnit.SECONDS);
            releaseAssets.countDown();
            String capturedOutput = output.get(5, TimeUnit.SECONDS);

            assertThat(parallelAssetRequests).isTrue();
            assertThat(capturedOutput)
                    .contains("[ProductionGradle] Downloading 4 Minecraft assets with 8 workers.")
                    .contains("[ProductionGradle] Minecraft assets 1/4")
                    .contains("[ProductionGradle] Minecraft assets 2/4")
                    .contains("[ProductionGradle] Minecraft assets 3/4")
                    .contains("[ProductionGradle] Minecraft assets 4/4")
                    .contains("[ProductionGradle] Minecraft assets complete.");
        }
    }

    @Test
    void reportsOfflineCacheMissWhenVersionMetadataIsAbsent() {
        LaunchSpec spec = validSpec("client", null, true);
        Path versionJson = versionDirectory(spec).resolve(VERSION + ".json");

        assertThatThrownBy(() -> resolver().prepare(spec))
                .hasMessageContaining("Offline cache miss")
                .hasMessageContaining(versionJson.toString());
    }

    @Test
    void respectsLaunchContextOfflineOverrideWhenVersionMetadataIsAbsent() throws IOException {
        LaunchSpec spec = validSpec("client", null, false);
        Path versionJson = versionDirectory(spec).resolve(VERSION + ".json");

        try (TestServer server = TestServer.start()) {
            assertThatThrownBy(() -> resolver(server.baseUri().resolve("/version-manifest.json"))
                            .prepare(new LaunchContext(spec, true)))
                    .hasMessageContaining("Offline cache miss")
                    .hasMessageContaining(versionJson.toString());

            assertThat(server.requestCount()).isZero();
        }
    }

    @Test
    void reportsOfflineCacheMissWhenReferencedLibraryIsAbsent() throws IOException {
        LaunchSpec spec = validSpec("client", null, true);
        Path versionDirectory = versionDirectory(spec);
        Path missingLibrary = spec.paths().cacheDir().resolve("libraries")
                .resolve("org/example/alpha/1.0/alpha-1.0.jar");
        writeFile(versionDirectory.resolve(VERSION + ".jar"));
        writeVersionJson(versionDirectory, clientVersionJson());

        assertThatThrownBy(() -> resolver().prepare(spec))
                .hasMessageContaining("Offline cache miss")
                .hasMessageContaining(missingLibrary.toString());
    }

    private VanillaResolver resolver() {
        return new VanillaResolver(new LaunchSpecValidator(), new LibraryRuleEvaluator("linux", "x86_64"));
    }

    private VanillaResolver resolver(URI manifestUri) {
        return new VanillaResolver(new LaunchSpecValidator(), new LibraryRuleEvaluator("linux", "x86_64"), manifestUri);
    }

    private VanillaResolver resolver(URI manifestUri, URI assetBaseUri) {
        return new VanillaResolver(
                new LaunchSpecValidator(),
                new LibraryRuleEvaluator("linux", "x86_64"),
                manifestUri,
                assetBaseUri,
                new dev.vfyjxf.gradle.launcher.core.download.Downloader());
    }

    private static URI assetBaseUri(TestServer server) {
        return server.baseUri().resolve("/assets/");
    }

    private LaunchSpec validSpec(String type) {
        return validSpec(type, null, false);
    }

    private LaunchSpec validSpec(String type, String mainClass) {
        return validSpec(type, mainClass, false);
    }

    private LaunchSpec validSpec(String type, String mainClass, boolean offline) {
        return validSpec(type, mainClass, offline, Map.of());
    }

    private LaunchSpec validSpec(
            String type,
            String mainClass,
            boolean offline,
            Map<String, Object> resolutionHints) {
        Path cache = tempDir.resolve("cache");
        Path instance = tempDir.resolve("run-" + type);
        return new LaunchSpec(
                1,
                type,
                type,
                VERSION,
                "vanilla",
                null,
                new LaunchPaths(
                        instance,
                        instance,
                        cache,
                        cache.resolve("assets"),
                        cache.resolve("libraries"),
                        instance.resolve("natives"),
                        instance.resolve("logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(),
                new LaunchSettings(
                        List.of("-DcustomCache=${launcher_cache}"),
                        List.of("--customGameDir=${game_directory}"),
                        Map.of(),
                        mainClass,
                        "server".equals(type)),
                resolutionHints,
                Map.of("offline", offline));
    }

    private Path versionDirectory(LaunchSpec spec) {
        return spec.paths().cacheDir().resolve("minecraft").resolve("versions").resolve(VERSION);
    }

    private Path writeFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "cached");
        return path;
    }

    private Path writeJar(Path path, String mainClass) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, jarBytes(mainClass));
        return path;
    }

    private byte[] jarBytes(String mainClass) throws IOException {
        Path jarPath = tempDir.resolve("generated-jars").resolve((mainClass == null ? "empty" : mainClass) + ".jar");
        Files.createDirectories(jarPath.getParent());
        if (mainClass == null) {
            try (OutputStream output = Files.newOutputStream(jarPath);
                    JarOutputStream jar = new JarOutputStream(output)) {
            }
            return Files.readAllBytes(jarPath);
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output, manifest)) {
        }
        return Files.readAllBytes(jarPath);
    }

    private byte[] nativeJarBytes() throws IOException {
        Path jarPath = tempDir.resolve("generated-jars/native.jar");
        Files.createDirectories(jarPath.getParent());
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new java.util.jar.JarEntry("native.bin"));
            jar.write("native".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new java.util.jar.JarEntry("META-INF/ignored.txt"));
            jar.write("ignored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return Files.readAllBytes(jarPath);
    }

    private void writeVersionJson(Path versionDirectory, String json) throws IOException {
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(VERSION + ".json"), json);
    }

    private String clientVersionJson() {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {
                    "id": "17"
                  },
                  "downloads": {
                    "client": {
                      "sha1": "client-sha1",
                      "size": 1,
                      "url": "https://example.invalid/client.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:alpha:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/alpha/1.0/alpha-1.0.jar",
                          "sha1": "alpha-sha1",
                          "size": 1,
                          "url": "https://example.invalid/alpha.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-Djava.library.path=${natives_directory}",
                      "-cp",
                      "${classpath}"
                    ],
                    "game": [
                      "--username",
                      "${auth_player_name}",
                      "--version",
                      "${version_name}",
                      "--gameDir",
                      "${game_directory}",
                      "--assetsDir",
                      "${assets_root}",
                      "--assetIndex",
                      "${assets_index_name}",
                      "--uuid",
                      "${auth_uuid}",
                      "--accessToken",
                      "${auth_access_token}",
                      "--userType",
                      "${user_type}",
                      "--versionType",
                      "${version_type}",
                      {
                        "rules": [
                          {
                            "action": "allow"
                          }
                        ],
                        "value": [
                          "--assetPath",
                          "${game_assets}"
                        ]
                      }
                    ]
                  }
                }
                """;
    }

    private String officialServerVersionJson() {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {
                    "id": "17"
                  },
                  "downloads": {
                    "server": {
                      "sha1": "server-sha1",
                      "size": 1,
                      "url": "https://example.invalid/server.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:alpha:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/alpha/1.0/alpha-1.0.jar",
                          "sha1": "alpha-sha1",
                          "size": 1,
                          "url": "https://example.invalid/alpha.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-Djava.library.path=${natives_directory}",
                      "-cp",
                      "${classpath}"
                    ],
                    "game": [
                      "--username",
                      "${auth_player_name}",
                      "--assetsDir",
                      "${assets_root}",
                      "--accessToken",
                      "${auth_access_token}"
                    ]
                  }
                }
                """;
    }

    private String serverVersionJsonWithClientMain() {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "downloads": {
                    "server": {
                      "sha1": "server-sha1",
                      "size": 1,
                      "url": "https://example.invalid/server.jar"
                    }
                  },
                  "libraries": []
                }
                """;
    }

    private String versionJson(URI baseUri, String serverJarSha1) {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "downloads": {
                    "server": {
                      "sha1": "%s",
                      "size": 1,
                      "url": "%s/server.jar"
                    }
                  },
                  "libraries": []
                }
                """.formatted(serverJarSha1, baseUri);
    }

    private String clientDownloadVersionJson(
            URI baseUri,
            String clientJarSha1,
            String libraryJarSha1,
            String nativeJarSha1,
            String assetIndexSha1) {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {
                    "id": "17",
                    "sha1": "%s",
                    "url": "%s/indexes/17.json"
                  },
                  "downloads": {
                    "client": {
                      "sha1": "%s",
                      "url": "%s/client.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:alpha:1.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/alpha/1.0/alpha-1.0.jar",
                          "sha1": "%s",
                          "url": "%s/library.jar"
                        }
                      }
                    },
                    {
                      "name": "org.example:native:1.0",
                      "natives": {
                        "%s": "%s"
                      },
                      "downloads": {
                        "classifiers": {
                          "%s": {
                            "path": "org/example/native/1.0/native-1.0-%s.jar",
                            "sha1": "%s",
                            "url": "%s/native.jar"
                          }
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [],
                    "game": []
                  }
                }
                """.formatted(assetIndexSha1, baseUri, clientJarSha1, baseUri, libraryJarSha1, baseUri,
                nativeOs(), nativeClassifier(), nativeClassifier(), nativeClassifier(), nativeJarSha1, baseUri);
    }

    private String clientAssetsOnlyVersionJson(
            URI baseUri,
            String clientJarSha1,
            String assetIndexSha1) {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assetIndex": {
                    "id": "17",
                    "sha1": "%s",
                    "url": "%s/indexes/17.json"
                  },
                  "downloads": {
                    "client": {
                      "sha1": "%s",
                      "url": "%s/client.jar"
                    }
                  },
                  "libraries": [],
                  "arguments": {
                    "jvm": [],
                    "game": []
                  }
                }
                """.formatted(assetIndexSha1, baseUri, clientJarSha1, baseUri);
    }

    private static String assetIndexJson(List<byte[]> assets) {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < assets.size(); index++) {
            entries.add("""
                    "minecraft/assets/%d.dat": {
                      "hash": "%s",
                      "size": %d
                    }
                    """.formatted(index, sha1(assets.get(index)), assets.get(index).length));
        }
        return """
                {
                  "objects": {
                    %s
                  }
                }
                """.formatted(String.join(",", entries));
    }

    private static String captureStandardOut(ThrowingAction action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static String nativeOs() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    private static String nativeClassifier() {
        return "natives-" + nativeOs();
    }

    private static String sha1(String value) {
        return sha1(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha1(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest is not available", exception);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final URI baseUri;
        private final ExecutorService executor;
        private final AtomicInteger requestCount = new AtomicInteger();

        private TestServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        static TestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.start();
            return new TestServer(server, executor);
        }

        URI baseUri() {
            return baseUri;
        }

        int requestCount() {
            return requestCount.get();
        }

        void response(String path, String body) {
            response(path, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        void response(String path, byte[] body) {
            server.createContext(path, exchange -> {
                requestCount.incrementAndGet();
                respond(exchange, body);
            });
        }

        void blockingResponse(
                String path,
                byte[] body,
                CountDownLatch started,
                CountDownLatch release) {
            server.createContext(path, exchange -> {
                requestCount.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    respond(exchange, body);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                }
            });
        }

        private static void respond(HttpExchange exchange, byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop((int) Duration.ZERO.toSeconds());
            executor.shutdownNow();
        }
    }
}
