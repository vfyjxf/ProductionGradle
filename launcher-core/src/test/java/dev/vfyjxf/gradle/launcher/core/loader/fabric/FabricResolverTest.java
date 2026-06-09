package dev.vfyjxf.gradle.launcher.core.loader.fabric;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport;
import dev.vfyjxf.gradle.launcher.core.minecraft.LibraryRuleEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.MINECRAFT_VERSION;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.resolver;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.spec;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.writeFile;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.writeMinecraftClientJar;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.writeMinecraftServerJar;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.writeMinecraftVersionJson;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.writeOfficialLibrary;
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.jarBytes;
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.sha1;
import static org.assertj.core.api.Assertions.assertThat;

class FabricResolverTest {
    private static final String LOADER_VERSION = "0.16.10";

    @TempDir
    Path tempDir;

    @Test
    void preparesClientFromCachedMinecraftAndFabricLoaderMetadata() throws Exception {
        LaunchSpec spec = spec(tempDir, "fabric", LOADER_VERSION, "client");
        Path minecraftLibrary = writeOfficialLibrary(spec);
        Path minecraftJar = writeMinecraftClientJar(spec);
        Path fabricLoader = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"));
        Path intermediary = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar"));
        writeMinecraftVersionJson(spec);
        writeFabricLoaderJson(spec);

        PreparedLaunch launch = new LauncherEngine().prepare(new LaunchContext(spec, false));

        assertThat(launch.classpath()).isNotEmpty();
        assertThat(launch.classpath()).contains(minecraftLibrary, minecraftJar, fabricLoader, intermediary);
        assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotClient");
        assertThat(launch.gameArgs()).contains(
                "--username",
                "LoaderPlayer",
                "--accessToken",
                "0",
                "--userType",
                "msa");
        assertThat(String.join(" ", launch.gameArgs())).doesNotContain("${auth_");
    }

    @Test
    void preparesServerFromCachedMinecraftAndFabricLoaderMetadata() throws Exception {
        LaunchSpec spec = spec(tempDir, "fabric", LOADER_VERSION, "server");
        Path minecraftLibrary = writeOfficialLibrary(spec);
        Path minecraftJar = writeMinecraftServerJar(spec);
        Path fabricLoader = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"));
        Path intermediary = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar"));
        writeMinecraftVersionJson(spec);
        writeFabricLoaderJson(spec);

        PreparedLaunch launch = fabricResolver().prepare(spec);

        assertThat(launch.classpath()).isNotEmpty();
        assertThat(launch.classpath()).contains(minecraftLibrary, minecraftJar, fabricLoader, intermediary);
        assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotServer");
    }

    @Test
    void preparesClientFromFabricProfileJsonVersionMetadata() throws Exception {
        LaunchSpec spec = spec(tempDir, "fabric", "0.19.3", "client");
        Path minecraftLibrary = writeOfficialLibrary(spec);
        Path minecraftJar = writeMinecraftClientJar(spec);
        Path asmLibrary = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"));
        Path fabricLoader = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"));
        writeMinecraftVersionJson(spec);
        writeFabricProfileJsonVersionMetadata(spec);

        PreparedLaunch launch = fabricResolver().prepare(spec);

        assertThat(launch.classpath()).contains(minecraftLibrary, minecraftJar, asmLibrary, fabricLoader);
        assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotClient");
        assertThat(launch.jvmArgs()).contains("-DFabricMcEmu= net.minecraft.client.main.Main ");
    }

    @Test
    void downloadsFabricServerJsonForServerRuns() throws Exception {
        byte[] serverJar = jarBytes(tempDir.resolve("remote-jars"), "server.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] fabricLoader = jarBytes(tempDir.resolve("remote-jars"), "fabric-loader.jar");

        try (RemoteResolutionTestSupport.TestServer server = RemoteResolutionTestSupport.TestServer.start()) {
            String versionJson = onlineMinecraftServerVersionJson(
                    server.baseUri(),
                    sha1(serverJar),
                    sha1(minecraftLibrary));
            server.response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson)));
            server.response("/version.json", versionJson);
            server.response("/server.jar", serverJar);
            server.response("/minecraft-lib.jar", minecraftLibrary);
            server.response(
                    "/fabric-meta/%s/0.19.3/server/json".formatted(MINECRAFT_VERSION),
                    fabricServerJsonVersionMetadata());
            server.response("/fabric-maven/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar", fabricLoader);

            LaunchSpec spec = spec(
                    tempDir,
                    "fabric",
                    "0.19.3",
                    "server",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "fabricMetaBaseUri", server.baseUri().resolve("/fabric-meta/").toString(),
                            "fabricMavenBaseUri", server.baseUri().resolve("/fabric-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = fabricResolver().prepare(new LaunchContext(spec, false));

            Path downloadedMetadata = spec.paths().cacheDir()
                    .resolve("loaders/fabric")
                    .resolve(MINECRAFT_VERSION)
                    .resolve("0.19.3")
                    .resolve("server-loader.json");
            assertThat(downloadedMetadata).isRegularFile();
            assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotServer");
        }
    }

    @Test
    void prefersCachedFabricServerJsonOverLegacyLoaderJsonForServerRuns() throws Exception {
        LaunchSpec spec = spec(tempDir, "fabric", "0.19.3", "server");
        writeOfficialLibrary(spec);
        writeMinecraftServerJar(spec);
        writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"));
        writeMinecraftVersionJson(spec);
        writeFabricProfileJsonVersionMetadata(spec);
        writeFabricServerJsonVersionMetadata(spec);

        PreparedLaunch launch = fabricResolver().prepare(spec);

        assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotServer");
    }

    @Test
    void skipsOfficialMinecraftArgumentsRejectedByRules() throws Exception {
        LaunchSpec spec = spec(tempDir, "fabric", LOADER_VERSION, "client");
        writeOfficialLibrary(spec);
        writeMinecraftClientJar(spec);
        writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"));
        writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar"));
        writeMinecraftVersionJsonWithWindowsOnlyArgument(spec);
        writeFabricLoaderJson(spec);

        PreparedLaunch launch = new FabricResolver(new ProductionLaunchSupport(
                new dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidator(),
                new LibraryRuleEvaluator("linux", "x86_64"))).prepare(spec);

        assertThat(launch.gameArgs()).doesNotContain("--windowsOnly");
    }

    @Test
    void downloadsMinecraftAndFabricMetadataWhenCacheIsEmpty() throws Exception {
        byte[] clientJar = jarBytes(tempDir.resolve("remote-jars"), "client.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] fabricLoader = jarBytes(tempDir.resolve("remote-jars"), "fabric-loader.jar");
        byte[] intermediary = jarBytes(tempDir.resolve("remote-jars"), "intermediary.jar");

        try (RemoteResolutionTestSupport.TestServer server = RemoteResolutionTestSupport.TestServer.start()) {
            String versionJson = onlineMinecraftVersionJson(
                    server.baseUri(),
                    sha1(clientJar),
                    sha1(minecraftLibrary));
            server.response("/version-manifest.json", """
                    {
                      "versions": [
                        {
                          "id": "1.21.1",
                          "url": "%s/version.json",
                          "sha1": "%s"
                        }
                      ]
                    }
                    """.formatted(server.baseUri(), sha1(versionJson)));
            server.response("/version.json", versionJson);
            server.response("/client.jar", clientJar);
            server.response("/minecraft-lib.jar", minecraftLibrary);
            server.response("/fabric-meta/%s/%s".formatted(MINECRAFT_VERSION, LOADER_VERSION), fabricMetadata());
            server.response("/fabric-maven/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar", fabricLoader);
            server.response("/fabric-maven/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar", intermediary);

            LaunchSpec spec = spec(
                    tempDir,
                    "fabric",
                    LOADER_VERSION,
                    "client",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "fabricMetaBaseUri", server.baseUri().resolve("/fabric-meta/").toString(),
                            "fabricMavenBaseUri", server.baseUri().resolve("/fabric-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = fabricResolver().prepare(new LaunchContext(spec, false));

            Path downloadedVersion = spec.paths().cacheDir()
                    .resolve("minecraft/versions")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(MINECRAFT_VERSION + ".json");
            Path downloadedClient = spec.paths().cacheDir()
                    .resolve("minecraft/versions")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(MINECRAFT_VERSION + ".jar");
            Path downloadedMinecraftLibrary = spec.paths().cacheDir()
                    .resolve("libraries/org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar");
            Path downloadedMetadata = spec.paths().cacheDir()
                    .resolve("loaders/fabric")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(LOADER_VERSION)
                    .resolve("loader.json");
            Path downloadedLoader = spec.paths().cacheDir()
                    .resolve("libraries/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar");
            Path downloadedIntermediary = spec.paths().cacheDir()
                    .resolve("libraries/net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar");

            assertThat(downloadedVersion).isRegularFile();
            assertThat(downloadedClient).isRegularFile();
            assertThat(downloadedMinecraftLibrary).isRegularFile();
            assertThat(downloadedMetadata).isRegularFile();
            assertThat(downloadedLoader).isRegularFile();
            assertThat(downloadedIntermediary).isRegularFile();
            assertThat(launch.classpath()).contains(
                    downloadedMinecraftLibrary,
                    downloadedClient,
                    downloadedLoader,
                    downloadedIntermediary);
            assertThat(launch.mainClass()).isEqualTo("net.fabricmc.loader.impl.launch.knot.KnotClient");
        }
    }

    private LoaderResolver fabricResolver() {
        return resolver("dev.vfyjxf.gradle.launcher.core.loader.fabric.FabricResolver");
    }

    private void writeFabricLoaderJson(LaunchSpec spec) throws IOException {
        Path metadata = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("fabric")
                .resolve(MINECRAFT_VERSION)
                .resolve(LOADER_VERSION)
                .resolve("loader.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
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
                      "server": [
                        {
                          "name": "net.fabricmc:fabric-loader:0.16.10"
                        }
                      ]
                    }
                  }
                }
                """);
    }

    private String fabricMetadata() {
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

    private void writeFabricProfileJsonVersionMetadata(LaunchSpec spec) throws IOException {
        Path metadata = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("fabric")
                .resolve(MINECRAFT_VERSION)
                .resolve("0.19.3")
                .resolve("loader.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                {
                  "id": "fabric-loader-0.19.3-1.21.1",
                  "inheritsFrom": "1.21.1",
                  "type": "release",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "arguments": {
                    "game": [],
                    "jvm": [
                      "-DFabricMcEmu= net.minecraft.client.main.Main "
                    ]
                  },
                  "libraries": [
                    {
                      "name": "org.ow2.asm:asm:9.10.1",
                      "url": "https://maven.fabricmc.net/"
                    },
                    {
                      "name": "net.fabricmc:fabric-loader:0.19.3",
                      "url": "https://maven.fabricmc.net/"
                    }
                  ]
                }
                """);
    }

    private void writeFabricServerJsonVersionMetadata(LaunchSpec spec) throws IOException {
        Path metadata = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("fabric")
                .resolve(MINECRAFT_VERSION)
                .resolve("0.19.3")
                .resolve("server-loader.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, fabricServerJsonVersionMetadata());
    }

    private String fabricServerJsonVersionMetadata() {
        return """
                {
                  "id": "fabric-loader-0.19.3-1.21.1",
                  "inheritsFrom": "1.21.1",
                  "type": "release",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotServer",
                  "arguments": {
                    "game": []
                  },
                  "libraries": [
                    {
                      "name": "net.fabricmc:fabric-loader:0.19.3",
                      "url": "https://maven.fabricmc.net/"
                    }
                  ]
                }
                """;
    }

    private String onlineMinecraftVersionJson(
            java.net.URI baseUri,
            String clientSha1,
            String minecraftLibrarySha1) {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "downloads": {
                    "client": {
                      "sha1": "%s",
                      "url": "%s/client.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:minecraft-lib:1.0.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar",
                          "sha1": "%s",
                          "url": "%s/minecraft-lib.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [],
                    "game": []
                  }
                }
                """.formatted(clientSha1, baseUri, minecraftLibrarySha1, baseUri);
    }

    private String onlineMinecraftServerVersionJson(
            java.net.URI baseUri,
            String serverSha1,
            String minecraftLibrarySha1) {
        return """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.server.Main",
                  "downloads": {
                    "server": {
                      "sha1": "%s",
                      "url": "%s/server.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:minecraft-lib:1.0.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar",
                          "sha1": "%s",
                          "url": "%s/minecraft-lib.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [],
                    "game": []
                  }
                }
                """.formatted(serverSha1, baseUri, minecraftLibrarySha1, baseUri);
    }

    private void writeMinecraftVersionJsonWithWindowsOnlyArgument(LaunchSpec spec) throws IOException {
        Path versionDirectory = spec.paths().cacheDir()
                .resolve("minecraft")
                .resolve("versions")
                .resolve(MINECRAFT_VERSION);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(MINECRAFT_VERSION + ".json"), """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "downloads": {
                    "client": {
                      "sha1": "client-sha1",
                      "size": 1,
                      "url": "https://example.invalid/client.jar"
                    }
                  },
                  "libraries": [
                    {
                      "name": "org.example:minecraft-lib:1.0.0",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar",
                          "sha1": "library-sha1",
                          "size": 1,
                          "url": "https://example.invalid/minecraft-lib.jar"
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "game": [
                      "--username",
                      "${auth_player_name}",
                      {
                        "rules": [
                          {
                            "action": "allow",
                            "os": {
                              "name": "windows"
                            }
                          }
                        ],
                        "value": [
                          "--windowsOnly",
                          "true"
                        ]
                      }
                    ],
                    "jvm": []
                  }
                }
                """);
    }
}
