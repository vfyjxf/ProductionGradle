package dev.vfyjxf.gradle.launcher.core.loader.forge;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchCommand;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSettings;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LauncherEngine;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
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
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.installerJarBytes;
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.jarBytes;
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.jarBytesWithEntry;
import static dev.vfyjxf.gradle.launcher.core.loader.RemoteResolutionTestSupport.sha1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForgeResolverTest {
    private static final String LOADER_VERSION = "52.0.28";

    @TempDir
    Path tempDir;

    @Test
    void preparesClientWithMinecraftClientArgumentsAndForgeLaunchTarget() throws Exception {
        LaunchSpec spec = spec(tempDir, "forge", LOADER_VERSION, "client");
        Path minecraftLibrary = writeOfficialLibrary(spec);
        Path clientJar = writeMinecraftClientJar(spec);
        Path bootstrapLauncher = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar"));
        Path forgeUniversal = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"));
        writeMinecraftVersionJson(spec);
        writeForgeClientInstallerVersionJson(spec);

        PreparedLaunch launch = new LauncherEngine().prepare(new LaunchContext(spec, false));

        assertThat(launch.classpath()).contains(minecraftLibrary, clientJar, bootstrapLauncher, forgeUniversal);
        assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertThat(launch.gameArgs()).contains(
                "--username",
                "LoaderPlayer",
                "--gameDir",
                spec.paths().workingDir().toString(),
                "--assetsDir",
                spec.paths().assetsDir().toString(),
                "--assetIndex",
                "17",
                "--launchTarget",
                "forgeclient");
        assertThat(String.join(" ", launch.gameArgs())).doesNotContain("${auth_");
    }

    @Test
    void preparesServerFromCachedForgeInstallerVersionAndCommandIncludesWorkingDirectory() throws Exception {
        LaunchSpec spec = spec(tempDir, "forge", LOADER_VERSION, "server");
        Path serverJar = writeMinecraftServerJar(spec);
        Path bootstrapLauncher = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar"));
        Path forgeUniversal = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"));
        writeMinecraftVersionJson(spec);
        writeForgeInstallerVersionJson(spec);

        PreparedLaunch launch = new LauncherEngine().prepare(new LaunchContext(spec, false));
        LaunchCommand command = new LauncherEngine().command(new LaunchContext(spec, false), launch);

        assertThat(launch.classpath()).contains(serverJar, bootstrapLauncher, forgeUniversal);
        assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertThat(launch.gameArgs()).contains("--serverDir", spec.paths().workingDir().toString(), "--nogui");
        assertThat(command.arguments()).contains("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertThat(command.arguments()).contains(spec.paths().workingDir().toString());
    }

    @Test
    void reportsOfflineCacheMissWhenForgeInstallerVersionIsAbsent() throws Exception {
        LaunchSpec spec = spec(tempDir, "forge", LOADER_VERSION, "server", Map.of(), Map.of("offline", true));
        Path missingVersion = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("forge")
                .resolve(MINECRAFT_VERSION)
                .resolve(LOADER_VERSION)
                .resolve("version.json");
        writeMinecraftServerJar(spec);
        writeMinecraftVersionJson(spec);

        assertThatThrownBy(() -> forgeResolver().prepare(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Offline cache miss")
                .hasMessageContaining(missingVersion.toString());
    }

    @Test
    void downloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty() throws Exception {
        assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(LOADER_VERSION);
    }

    @Test
    void downloadsForgeInstallerUsingArtifactVersionWhenLoaderVersionIsMapped() throws Exception {
        assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(
                LOADER_VERSION + "_mapped_official_1.21.1");
    }

    @Test
    void runsForgeInstallProfileProcessorsBeforeResolvingGeneratedClientArtifact() throws Exception {
        String processorCoordinate = "org.example:processor:1.0.0";
        byte[] clientJar = jarBytes(tempDir.resolve("remote-jars"), "client.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] bootstrapLauncher = jarBytes(tempDir.resolve("remote-jars"), "bootstraplauncher.jar");
        byte[] processor = processorJarBytes(tempDir.resolve("remote-jars"), "processor.jar");
        String loaderVersionJson = forgeModernClientVersionJson();
        String installProfileJson = forgeInstallProfileJson(processorCoordinate);
        byte[] installer = installerJarBytes(
                tempDir.resolve("remote-jars"),
                "forge-installer.jar",
                loaderVersionJson,
                installProfileJson);

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
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-installer.jar", installer);
            server.response("/forge-maven/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar", bootstrapLauncher);
            server.response("/forge-maven/org/example/processor/1.0.0/processor-1.0.0.jar", processor);

            LaunchSpec spec = spec(
                    tempDir,
                    "forge",
                    LOADER_VERSION,
                    "client",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "forgeMavenBaseUri", server.baseUri().resolve("/forge-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = forgeResolver().prepare(new LaunchContext(spec, false));

            Path generatedClient = spec.paths().cacheDir()
                    .resolve("libraries/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-client.jar");

            assertThat(generatedClient).hasContent("generated:" + generatedClient);
            assertThat(launch.classpath()).contains(generatedClient);
            assertThat(launch.gameArgs()).contains("--launchTarget", "forgeclient");
        }
    }

    @Test
    void usesForgeInstallProfileServerArgumentsFromColdCache() throws Exception {
        String processorCoordinate = "org.example:processor:1.0.0";
        byte[] serverJar = jarBytes(tempDir.resolve("remote-jars"), "server.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] processor = processorJarBytes(tempDir.resolve("remote-jars"), "processor.jar");
        byte[] shim = jarBytes(tempDir.resolve("remote-jars"), "forge-shim.jar");
        byte[] universal = jarBytes(tempDir.resolve("remote-jars"), "forge-universal.jar");
        String loaderVersionJson = forgeModernServerVersionJson();
        String installProfileJson = forgeServerInstallProfileJson(processorCoordinate);
        byte[] installer = installerJarBytes(
                tempDir.resolve("remote-jars"),
                "forge-installer.jar",
                loaderVersionJson,
                installProfileJson);

        try (RemoteResolutionTestSupport.TestServer server = RemoteResolutionTestSupport.TestServer.start()) {
            String versionJson = onlineMinecraftVersionJson(
                    server.baseUri(),
                    sha1(serverJar),
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
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-installer.jar", installer);
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-shim.jar", shim);
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar", universal);
            server.response("/forge-maven/org/example/processor/1.0.0/processor-1.0.0.jar", processor);

            LaunchSpec spec = spec(
                    tempDir,
                    "forge",
                    LOADER_VERSION,
                    "server",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "forgeMavenBaseUri", server.baseUri().resolve("/forge-maven/").toString()),
                    Map.of());
            spec = withGameArgs(spec, java.util.List.of("nogui"));

            PreparedLaunch launch = forgeResolver().prepare(new LaunchContext(spec, false));
            LaunchCommand command = new LauncherEngine().command(new LaunchContext(spec, false), launch);

            Path libraries = spec.paths().cacheDir().resolve("libraries");
            Path generatedClient = libraries.resolve("net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-client.jar");
            Path generatedServer = libraries.resolve("net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-server.jar");
            Path serverArgs = libraries.resolve("net/minecraftforge/forge/1.21.1-52.0.28/unix_args.txt");
            Path shimJar = libraries.resolve("net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-shim.jar");

            assertThat(generatedClient).doesNotExist();
            assertThat(generatedServer).hasContent("generated:" + generatedServer);
            assertThat(serverArgs).hasContent("-Djava.net.preferIPv6Addresses=system -jar forge-1.21.1-52.0.28-shim.jar");
            assertThat(command.arguments()).containsSubsequence(
                    "-Djava.net.preferIPv6Addresses=system",
                    "-jar",
                    shimJar.toString(),
                    "nogui");
            assertThat(command.arguments()).doesNotContain("-cp", "forge_client", "cpw.mods.bootstraplauncher.BootstrapLauncher");
            assertThat(command.arguments()).noneMatch(argument -> argument.equals("forge-1.21.1-52.0.28-shim.jar")
                    || argument.contains("=libraries")
                    || argument.startsWith("libraries/")
                    || argument.contains(File.pathSeparator + "libraries/"));
        }
    }

    private void assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(String loaderVersion)
            throws Exception {
        byte[] clientJar = jarBytes(tempDir.resolve("remote-jars"), "client.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] bootstrapLauncher = jarBytes(tempDir.resolve("remote-jars"), "bootstraplauncher.jar");
        byte[] forgeUniversal = jarBytes(tempDir.resolve("remote-jars"), "forge-universal.jar");
        String loaderVersionJson = forgeInstallerVersionJson("""
                [
                  "--launchTarget",
                  "forgeclient",
                  "--fml.mcVersion",
                  "${minecraft_version}"
                ]
                """);
        byte[] installer = installerJarBytes(tempDir.resolve("remote-jars"), "forge-installer.jar", loaderVersionJson);

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
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-installer.jar", installer);
            server.response("/forge-maven/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar", bootstrapLauncher);
            server.response("/forge-maven/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar", forgeUniversal);

            LaunchSpec spec = spec(
                    tempDir,
                    "forge",
                    loaderVersion,
                    "client",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "forgeMavenBaseUri", server.baseUri().resolve("/forge-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = forgeResolver().prepare(new LaunchContext(spec, false));

            Path extractedVersion = spec.paths().cacheDir()
                    .resolve("loaders/forge")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(loaderVersion)
                    .resolve("version.json");
            Path downloadedInstaller = spec.paths().cacheDir()
                    .resolve("loaders/forge")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(loaderVersion)
                    .resolve("forge-1.21.1-52.0.28-installer.jar");
            Path downloadedMinecraftLibrary = spec.paths().cacheDir()
                    .resolve("libraries/org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar");
            Path downloadedBootstrap = spec.paths().cacheDir()
                    .resolve("libraries/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar");
            Path downloadedUniversal = spec.paths().cacheDir()
                    .resolve("libraries/net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar");

            assertThat(extractedVersion).isRegularFile();
            assertThat(downloadedInstaller).isRegularFile();
            assertThat(downloadedMinecraftLibrary).isRegularFile();
            assertThat(downloadedBootstrap).isRegularFile();
            assertThat(downloadedUniversal).isRegularFile();
            assertThat(launch.classpath()).contains(downloadedMinecraftLibrary, downloadedBootstrap, downloadedUniversal);
            assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
            assertThat(launch.gameArgs()).contains("--launchTarget", "forgeclient");
        }
    }

    private LoaderResolver forgeResolver() {
        return resolver("dev.vfyjxf.gradle.launcher.core.loader.forge.ForgeResolver");
    }

    private LaunchSpec withGameArgs(LaunchSpec spec, java.util.List<String> gameArgs) {
        return new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                spec.minecraftVersion(),
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                new LaunchSettings(
                        spec.launch().jvmArgs(),
                        gameArgs,
                        spec.launch().environment(),
                        spec.launch().mainClass(),
                        spec.launch().eulaAccepted()),
                spec.resolutionHints(),
                spec.gradle());
    }

    private void writeForgeInstallerVersionJson(LaunchSpec spec) throws IOException {
        writeForgeInstallerVersionJson(spec, """
                [
                  "--launchTarget",
                  "forgeserver",
                  "--fml.mcVersion",
                  "${minecraft_version}",
                  "--serverDir",
                  "${game_directory}",
                  "--nogui"
                ]
                """);
    }

    private void writeForgeClientInstallerVersionJson(LaunchSpec spec) throws IOException {
        writeForgeInstallerVersionJson(spec, """
                [
                  "--launchTarget",
                  "forgeclient",
                  "--fml.mcVersion",
                  "${minecraft_version}"
                ]
                """);
    }

    private void writeForgeInstallerVersionJson(LaunchSpec spec, String gameArguments) throws IOException {
        Path metadata = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("forge")
                .resolve(MINECRAFT_VERSION)
                .resolve(LOADER_VERSION)
                .resolve("version.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                {
                  "id": "forge-1.21.1-52.0.28",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.0"
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:universal",
                      "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-DlibraryDirectory=${library_directory}"
                    ],
                    "game": %s
                  }
                }
                """.formatted(gameArguments));
    }

    private String forgeInstallerVersionJson(String gameArguments) {
        return """
                {
                  "id": "forge-1.21.1-52.0.28",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.0"
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:universal",
                      "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-DlibraryDirectory=${library_directory}"
                    ],
                    "game": %s
                  }
                }
                """.formatted(gameArguments);
    }

    private String forgeModernClientVersionJson() {
        return """
                {
                  "id": "forge-1.21.1-52.0.28",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.2"
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:client",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-client.jar",
                          "url": ""
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-DlibraryDirectory=${library_directory}"
                    ],
                    "game": [
                      "--launchTarget",
                      "forgeclient",
                      "--fml.mcVersion",
                      "1.21.1"
                    ]
                  }
                }
                """;
    }

    private String forgeModernServerVersionJson() {
        return """
                {
                  "id": "forge-1.21.1-52.0.28",
                  "mainClass": "net.minecraftforge.bootstrap.ForgeBootstrap",
                  "libraries": [
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:universal",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"
                        }
                      }
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:client",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-client.jar",
                          "url": ""
                        }
                      }
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-Djava.net.preferIPv6Addresses=system"
                    ],
                    "game": [
                      "--launchTarget",
                      "forge_client"
                    ]
                  }
                }
                """;
    }

    private String forgeInstallProfileJson(String processorCoordinate) {
        return """
                {
                  "spec": 1,
                  "profile": "Forge",
                  "version": "forge-1.21.1-52.0.28",
                  "data": {
                    "PATCHED": {
                      "client": "[net.minecraftforge:forge:1.21.1-52.0.28:client]"
                    }
                  },
                  "processors": [
                    {
                      "sides": ["client"],
                      "jar": "%1$s",
                      "classpath": ["%1$s"],
                      "args": [
                        "--write",
                        "{PATCHED}",
                        "generated:{PATCHED}"
                      ]
                    }
                  ],
                  "libraries": [
                    {
                      "name": "%1$s",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/processor/1.0.0/processor-1.0.0.jar"
                        }
                      }
                    }
                  ]
                }
                """.formatted(processorCoordinate);
    }

    private String forgeServerInstallProfileJson(String processorCoordinate) {
        return """
                {
                  "spec": 1,
                  "profile": "Forge",
                  "version": "forge-1.21.1-52.0.28",
                  "data": {
                    "PATCHED": {
                      "client": "[net.minecraftforge:forge:1.21.1-52.0.28:client]",
                      "server": "[net.minecraftforge:forge:1.21.1-52.0.28:server]"
                    }
                  },
                  "processors": [
                    {
                      "jar": "%1$s",
                      "classpath": ["%1$s"],
                      "args": [
                        "--write",
                        "{PATCHED}",
                        "generated:{PATCHED}"
                      ]
                    },
                    {
                      "sides": ["server"],
                      "jar": "%1$s",
                      "classpath": ["%1$s"],
                      "args": [
                        "--write",
                        "{ROOT}/libraries/net/minecraftforge/forge/1.21.1-52.0.28/unix_args.txt",
                        "-Djava.net.preferIPv6Addresses=system -jar forge-1.21.1-52.0.28-shim.jar"
                      ]
                    }
                  ],
                  "libraries": [
                    {
                      "name": "%1$s",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/processor/1.0.0/processor-1.0.0.jar"
                        }
                      }
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:shim",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-shim.jar"
                        }
                      }
                    },
                    {
                      "name": "net.minecraftforge:forge:1.21.1-52.0.28:universal",
                      "downloads": {
                        "artifact": {
                          "path": "net/minecraftforge/forge/1.21.1-52.0.28/forge-1.21.1-52.0.28-universal.jar"
                        }
                      }
                    }
                  ]
                }
                """.formatted(processorCoordinate);
    }

    private byte[] processorJarBytes(Path directory, String fileName) throws IOException {
        Path sourceDirectory = tempDir.resolve("processor-src");
        Path classesDirectory = tempDir.resolve("processor-classes");
        Path source = sourceDirectory.resolve("org/example/InstallProcessor.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDirectory);
        Files.writeString(source, """
                package org.example;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class InstallProcessor {
                    public static void main(String[] args) throws Exception {
                        for (int index = 0; index < args.length; index++) {
                            if ("--write".equals(args[index])) {
                                Path target = Path.of(args[index + 1]);
                                Files.createDirectories(target.getParent());
                                Files.writeString(target, args[index + 2]);
                                index += 2;
                            }
                        }
                    }
                }
                """);
        int result = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-d",
                classesDirectory.toString(),
                source.toString());
        if (result != 0) {
            throw new IOException("test install processor compilation failed with exit code " + result);
        }
        return jarBytesWithEntry(
                directory,
                fileName,
                "org.example.InstallProcessor",
                "org/example/InstallProcessor.class",
                Files.readAllBytes(classesDirectory.resolve("org/example/InstallProcessor.class")));
    }

    private String onlineMinecraftVersionJson(
            java.net.URI baseUri,
            String clientSha1,
            String minecraftLibrarySha1) {
        return onlineMinecraftVersionJson(baseUri, clientSha1, clientSha1, minecraftLibrarySha1);
    }

    private String onlineMinecraftVersionJson(
            java.net.URI baseUri,
            String clientSha1,
            String serverSha1,
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
                    },
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
                """.formatted(clientSha1, baseUri, serverSha1, baseUri, minecraftLibrarySha1, baseUri);
    }
}
