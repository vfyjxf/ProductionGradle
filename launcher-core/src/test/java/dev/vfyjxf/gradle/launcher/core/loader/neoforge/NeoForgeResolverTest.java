package dev.vfyjxf.gradle.launcher.core.loader.neoforge;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchCommand;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
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
import java.util.List;
import java.util.Map;

import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.MINECRAFT_VERSION;
import static dev.vfyjxf.gradle.launcher.core.loader.ProductionResolverTestFixtures.joinedClasspath;
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

class NeoForgeResolverTest {
    private static final String LOADER_VERSION = "21.1.172";

    @TempDir
    Path tempDir;

    @Test
    void preparesClientWithMinecraftClientArgumentsAndWithoutDevelopmentClasspath() throws Exception {
        Path developmentJar = tempDir.resolve("dev-only").resolve("dev-only.jar");
        LaunchSpec spec = spec(
                tempDir,
                "neoforge",
                LOADER_VERSION,
                "client",
                Map.of("developmentClasspath", List.of(developmentJar.toString())),
                Map.of("developmentClasspath", List.of(developmentJar.toString())));
        Path minecraftLibrary = writeOfficialLibrary(spec);
        Path clientJar = writeMinecraftClientJar(spec);
        Path bootstrapLauncher = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar"));
        Path neoForgeUniversal = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"));
        writeFile(developmentJar);
        writeMinecraftVersionJson(spec);
        writeNeoForgeClientInstallerVersionJson(spec);

        PreparedLaunch launch = new LauncherEngine().prepare(new LaunchContext(spec, false));

        assertThat(launch.classpath()).contains(minecraftLibrary, clientJar, bootstrapLauncher, neoForgeUniversal);
        assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertThat(launch.gameArgs()).contains(
                "--username",
                "LoaderPlayer",
                "--assetsDir",
                spec.paths().assetsDir().toString(),
                "--assetIndex",
                "17",
                "--launchTarget",
                "neoforgeclient");
        assertThat(joinedClasspath(launch)).doesNotContain("dev-only.jar");
        assertThat(String.join(" ", launch.gameArgs())).doesNotContain("${auth_");
    }

    @Test
    void preparesServerFromCachedNeoForgeInstallerVersionWithoutDevelopmentClasspath() throws Exception {
        Path developmentJar = tempDir.resolve("dev-only").resolve("dev-only.jar");
        LaunchSpec spec = spec(
                tempDir,
                "neoforge",
                LOADER_VERSION,
                "server",
                Map.of("developmentClasspath", List.of(developmentJar.toString())),
                Map.of("developmentClasspath", List.of(developmentJar.toString())));
        Path serverJar = writeMinecraftServerJar(spec);
        Path bootstrapLauncher = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar"));
        Path neoForgeUniversal = writeFile(spec.paths().cacheDir().resolve("libraries")
                .resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"));
        writeFile(developmentJar);
        writeMinecraftVersionJson(spec);
        writeNeoForgeInstallerVersionJson(spec);

        PreparedLaunch launch = new LauncherEngine().prepare(new LaunchContext(spec, true));
        LaunchCommand command = new LauncherEngine().command(new LaunchContext(spec, true), launch);

        assertThat(launch.classpath()).contains(serverJar, bootstrapLauncher, neoForgeUniversal);
        assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertThat(launch.gameArgs()).contains("--launchTarget", "neoforgeserver");
        assertThat(launch.classpath()).doesNotContain(developmentJar);
        assertThat(joinedClasspath(launch)).doesNotContain("dev-only.jar");
        assertThat(String.join(" ", command.arguments())).doesNotContain("dev-only.jar");
    }

    @Test
    void downloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty() throws Exception {
        assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(LOADER_VERSION);
    }

    @Test
    void downloadsNeoForgeInstallerUsingArtifactVersionWhenLoaderVersionIsMapped() throws Exception {
        assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(
                LOADER_VERSION + "_mapped_official_1.21.1");
    }

    @Test
    void runsNeoForgeInstallProfileProcessorsAndUsesGeneratedProductionArtifacts() throws Exception {
        String processorCoordinate = "org.example:processor:1.0.0";
        String neoFormVersion = "20240808.144430";
        byte[] clientJar = jarBytes(tempDir.resolve("remote-jars"), "client.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] bootstrapLauncher = jarBytes(tempDir.resolve("remote-jars"), "bootstraplauncher.jar");
        byte[] secureJarHandler = jarBytes(tempDir.resolve("remote-jars"), "securejarhandler.jar");
        byte[] fmlLoader = jarBytes(tempDir.resolve("remote-jars"), "fml-loader.jar");
        byte[] processor = processorJarBytes(tempDir.resolve("remote-jars"), "processor.jar");
        byte[] neoForgeUniversal = jarBytesWithEntry(
                tempDir.resolve("remote-jars"),
                "neoforge-universal.jar",
                "META-INF/neoforge.mods.toml",
                "[[mods]]\nmodId=\"neoforge\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String loaderVersionJson = neoForgeModernClientVersionJson(neoFormVersion);
        String installProfileJson = neoForgeInstallProfileJson(processorCoordinate, neoFormVersion);
        byte[] installer = installerJarBytes(
                tempDir.resolve("remote-jars"),
                "neoforge-installer.jar",
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
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-installer.jar", installer);
            server.response("/neoforge-maven/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar", bootstrapLauncher);
            server.response("/neoforge-maven/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar", secureJarHandler);
            server.response("/neoforge-maven/net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar", fmlLoader);
            server.response("/neoforge-maven/org/example/processor/1.0.0/processor-1.0.0.jar", processor);
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar", neoForgeUniversal);

            LaunchSpec spec = spec(
                    tempDir,
                    "neoforge",
                    LOADER_VERSION,
                    "client",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "neoforgeMavenBaseUri", server.baseUri().resolve("/neoforge-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = neoForgeResolver().prepare(new LaunchContext(spec, false));
            LaunchCommand command = new LauncherEngine().command(new LaunchContext(spec, false), launch);

            Path libraries = spec.paths().cacheDir().resolve("libraries");
            Path generatedClient = libraries.resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-client.jar");
            Path generatedMinecraftSrg = libraries.resolve("net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar");
            Path generatedMinecraftExtra = libraries.resolve("net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar");
            Path downloadedUniversal = libraries.resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar");

            assertThat(generatedClient).hasContent("generated:" + generatedClient);
            assertThat(generatedMinecraftSrg).hasContent("generated:" + generatedMinecraftSrg);
            assertThat(generatedMinecraftExtra).hasContent("generated:" + generatedMinecraftExtra);
            assertThat(downloadedUniversal).isRegularFile();
            assertThat(launch.jvmArgs()).contains("-DlibraryDirectory=" + libraries);
            assertThat(launch.classpath()).doesNotContain(downloadedUniversal, generatedClient);
            assertThat(command.arguments()).doesNotContain(downloadedUniversal.toString(), generatedClient.toString());
        }
    }

    @Test
    void usesNeoForgeInstallProfileServerArgumentsForServerLaunch() throws Exception {
        String processorCoordinate = "org.example:processor:1.0.0";
        String neoFormVersion = "20240808.144430";
        byte[] serverJar = jarBytes(tempDir.resolve("remote-jars"), "server.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] bootstrapLauncher = jarBytes(tempDir.resolve("remote-jars"), "bootstraplauncher.jar");
        byte[] secureJarHandler = jarBytes(tempDir.resolve("remote-jars"), "securejarhandler.jar");
        byte[] fmlLoader = jarBytes(tempDir.resolve("remote-jars"), "fml-loader.jar");
        byte[] processor = processorJarBytes(tempDir.resolve("remote-jars"), "processor.jar");
        byte[] neoForgeUniversal = jarBytesWithEntry(
                tempDir.resolve("remote-jars"),
                "neoforge-universal.jar",
                "META-INF/neoforge.mods.toml",
                "[[mods]]\nmodId=\"neoforge\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String loaderVersionJson = neoForgeModernClientVersionJson(neoFormVersion);
        String installProfileJson = neoForgeServerInstallProfileJson(processorCoordinate, neoFormVersion);
        byte[] installer = installerJarBytes(
                tempDir.resolve("remote-jars"),
                "neoforge-installer.jar",
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
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-installer.jar", installer);
            server.response("/neoforge-maven/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar", bootstrapLauncher);
            server.response("/neoforge-maven/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar", secureJarHandler);
            server.response("/neoforge-maven/net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar", fmlLoader);
            server.response("/neoforge-maven/org/example/processor/1.0.0/processor-1.0.0.jar", processor);
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar", neoForgeUniversal);

            LaunchSpec spec = spec(
                    tempDir,
                    "neoforge",
                    LOADER_VERSION,
                    "server",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "neoforgeMavenBaseUri", server.baseUri().resolve("/neoforge-maven/").toString()),
                    Map.of("gameArgs", List.of("nogui")));

            PreparedLaunch launch = neoForgeResolver().prepare(new LaunchContext(spec, false));
            LaunchCommand command = new LauncherEngine().command(new LaunchContext(spec, false), launch);

            Path libraries = spec.paths().cacheDir().resolve("libraries");
            Path generatedServer = libraries.resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-server.jar");
            Path generatedMinecraftSrg = libraries.resolve("net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar");
            Path generatedMinecraftExtra = libraries.resolve("net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-extra.jar");
            Path serverArgs = libraries.resolve("net/neoforged/neoforge/21.1.172/unix_args.txt");

            assertThat(generatedServer).hasContent("generated:" + generatedServer);
            assertThat(generatedMinecraftSrg).hasContent("generated:" + generatedMinecraftSrg);
            assertThat(generatedMinecraftExtra).hasContent("generated:" + generatedMinecraftExtra);
            assertThat(Files.readString(serverArgs)).contains("--launchTarget forgeserver");
            assertThat(command.arguments()).containsSubsequence("--launchTarget", "forgeserver");
            assertThat(command.arguments()).contains("-DlibraryDirectory=" + libraries);
            assertThat(command.arguments()).doesNotContain("forgeclient", "-cp");
            assertThat(command.arguments()).noneMatch(argument -> argument.equals("libraries")
                    || argument.contains("=libraries")
                    || argument.startsWith("libraries/")
                    || argument.contains(File.pathSeparator + "libraries/"));
            assertThat(command.arguments()).anySatisfy(argument -> assertThat(argument)
                    .startsWith("-DlegacyClassPath=")
                    .contains(libraries.resolve("net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar").toString()
                            + File.pathSeparator
                            + libraries.resolve("net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar")));
        }
    }

    private void assertDownloadsInstallerExtractsVersionJsonAndDownloadsLibrariesWhenCacheIsEmpty(String loaderVersion)
            throws Exception {
        byte[] clientJar = jarBytes(tempDir.resolve("remote-jars"), "client.jar");
        byte[] minecraftLibrary = jarBytes(tempDir.resolve("remote-jars"), "minecraft-lib.jar");
        byte[] bootstrapLauncher = jarBytes(tempDir.resolve("remote-jars"), "bootstraplauncher.jar");
        byte[] neoForgeUniversal = jarBytes(tempDir.resolve("remote-jars"), "neoforge-universal.jar");
        String loaderVersionJson = neoForgeInstallerVersionJson("""
                [
                  "--launchTarget",
                  "neoforgeclient",
                  "--fml.neoForgeVersion",
                  "${loader_version}"
                ]
                """);
        byte[] installer = installerJarBytes(tempDir.resolve("remote-jars"), "neoforge-installer.jar", loaderVersionJson);

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
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-installer.jar", installer);
            server.response("/neoforge-maven/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar", bootstrapLauncher);
            server.response("/neoforge-maven/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar", neoForgeUniversal);

            LaunchSpec spec = spec(
                    tempDir,
                    "neoforge",
                    loaderVersion,
                    "client",
                    Map.of(
                            "minecraftVersionManifestUri", server.baseUri().resolve("/version-manifest.json").toString(),
                            "neoforgeMavenBaseUri", server.baseUri().resolve("/neoforge-maven/").toString()),
                    Map.of());

            PreparedLaunch launch = neoForgeResolver().prepare(new LaunchContext(spec, false));

            Path extractedVersion = spec.paths().cacheDir()
                    .resolve("loaders/neoforge")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(loaderVersion)
                    .resolve("version.json");
            Path downloadedInstaller = spec.paths().cacheDir()
                    .resolve("loaders/neoforge")
                    .resolve(MINECRAFT_VERSION)
                    .resolve(loaderVersion)
                    .resolve("neoforge-21.1.172-installer.jar");
            Path downloadedMinecraftLibrary = spec.paths().cacheDir()
                    .resolve("libraries/org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar");
            Path downloadedBootstrap = spec.paths().cacheDir()
                    .resolve("libraries/cpw/mods/bootstraplauncher/2.0.0/bootstraplauncher-2.0.0.jar");
            Path downloadedUniversal = spec.paths().cacheDir()
                    .resolve("libraries/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar");

            assertThat(extractedVersion).isRegularFile();
            assertThat(downloadedInstaller).isRegularFile();
            assertThat(downloadedMinecraftLibrary).isRegularFile();
            assertThat(downloadedBootstrap).isRegularFile();
            assertThat(downloadedUniversal).isRegularFile();
            assertThat(launch.classpath()).contains(downloadedMinecraftLibrary, downloadedBootstrap, downloadedUniversal);
            assertThat(launch.mainClass()).isEqualTo("cpw.mods.bootstraplauncher.BootstrapLauncher");
            assertThat(launch.gameArgs()).contains("--launchTarget", "neoforgeclient");
        }
    }

    private LoaderResolver neoForgeResolver() {
        return resolver("dev.vfyjxf.gradle.launcher.core.loader.neoforge.NeoForgeResolver");
    }

    private void writeNeoForgeInstallerVersionJson(LaunchSpec spec) throws IOException {
        writeNeoForgeInstallerVersionJson(spec, """
                [
                  "--launchTarget",
                  "neoforgeserver",
                  "--fml.neoForgeVersion",
                  "${loader_version}",
                  "--serverDir",
                  "${game_directory}",
                  "--nogui"
                ]
                """);
    }

    private void writeNeoForgeClientInstallerVersionJson(LaunchSpec spec) throws IOException {
        writeNeoForgeInstallerVersionJson(spec, """
                [
                  "--launchTarget",
                  "neoforgeclient",
                  "--fml.neoForgeVersion",
                  "${loader_version}"
                ]
                """);
    }

    private void writeNeoForgeInstallerVersionJson(LaunchSpec spec, String gameArguments) throws IOException {
        Path metadata = spec.paths().cacheDir()
                .resolve("loaders")
                .resolve("neoforge")
                .resolve(MINECRAFT_VERSION)
                .resolve(LOADER_VERSION)
                .resolve("version.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                {
                  "id": "neoforge-21.1.172",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.0"
                    },
                    {
                      "name": "net.neoforged:neoforge:21.1.172:universal",
                      "path": "net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"
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

    private String neoForgeInstallerVersionJson(String gameArguments) {
        return """
                {
                  "id": "neoforge-21.1.172",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.0"
                    },
                    {
                      "name": "net.neoforged:neoforge:21.1.172:universal",
                      "path": "net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"
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

    private String neoForgeModernClientVersionJson(String neoFormVersion) {
        return """
                {
                  "id": "neoforge-21.1.172",
                  "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
                  "libraries": [
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.2"
                    },
                    {
                      "name": "cpw.mods:securejarhandler:3.0.8"
                    }
                  ],
                  "arguments": {
                    "jvm": [
                      "-DlibraryDirectory=${library_directory}",
                      "-p",
                      "${library_directory}/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar${classpath_separator}${library_directory}/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
                      "--add-modules",
                      "ALL-MODULE-PATH"
                    ],
                    "game": [
                      "--launchTarget",
                      "forgeclient",
                      "--fml.neoForgeVersion",
                      "${loader_version}",
                      "--fml.mcVersion",
                      "1.21.1",
                      "--fml.neoFormVersion",
                      "%s"
                    ]
                  }
                }
                """.formatted(neoFormVersion);
    }

    private String neoForgeInstallProfileJson(String processorCoordinate, String neoFormVersion) {
        return """
                {
                  "spec": 1,
                  "profile": "NeoForge",
                  "version": "neoforge-21.1.172",
                  "data": {
                    "PATCHED": {
                      "client": "[net.neoforged:neoforge:21.1.172:client]"
                    },
                    "MC_SRG": {
                      "client": "[net.minecraft:client:1.21.1-%1$s:srg]"
                    },
                    "MC_EXTRA": {
                      "client": "[net.minecraft:client:1.21.1-%1$s:extra]"
                    }
                  },
                  "processors": [
                    {
                      "sides": ["client"],
                      "jar": "%2$s",
                      "classpath": ["%2$s"],
                      "args": [
                        "--write",
                        "{PATCHED}",
                        "generated:{PATCHED}",
                        "--write",
                        "{MC_SRG}",
                        "generated:{MC_SRG}",
                        "--write",
                        "{MC_EXTRA}",
                        "generated:{MC_EXTRA}"
                      ]
                    }
                  ],
                  "libraries": [
                    {
                      "name": "%2$s",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/processor/1.0.0/processor-1.0.0.jar"
                        }
                      }
                    },
                    {
                      "name": "net.neoforged:neoforge:21.1.172:universal",
                      "downloads": {
                        "artifact": {
                          "path": "net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"
                        }
                      }
                    }
                  ]
                }
                """.formatted(neoFormVersion, processorCoordinate);
    }

    private String neoForgeServerInstallProfileJson(String processorCoordinate, String neoFormVersion) {
        return """
                {
                  "spec": 1,
                  "profile": "NeoForge",
                  "version": "neoforge-21.1.172",
                  "data": {
                    "PATCHED": {
                      "server": "[net.neoforged:neoforge:21.1.172:server]"
                    },
                    "MC_SRG": {
                      "server": "[net.minecraft:server:1.21.1-%1$s:srg]"
                    },
                    "MC_EXTRA": {
                      "server": "[net.minecraft:server:1.21.1-%1$s:extra]"
                    }
                  },
                  "processors": [
                    {
                      "sides": ["server"],
                      "jar": "%2$s",
                      "classpath": ["%2$s"],
                      "args": [
                        "--write",
                        "{PATCHED}",
                        "generated:{PATCHED}",
                        "--write",
                        "{MC_SRG}",
                        "generated:{MC_SRG}",
                        "--write",
                        "{MC_EXTRA}",
                        "generated:{MC_EXTRA}",
                        "--write",
                        "{ROOT}/libraries/net/neoforged/neoforge/21.1.172/unix_args.txt",
                        "-DlibraryDirectory=libraries -DlegacyClassPath=libraries/net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar%3$slibraries/net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar cpw.mods.bootstraplauncher.BootstrapLauncher --launchTarget forgeserver --fml.neoForgeVersion 21.1.172 --fml.mcVersion 1.21.1 --fml.neoFormVersion %1$s"
                      ]
                    }
                  ],
                  "libraries": [
                    {
                      "name": "%2$s",
                      "downloads": {
                        "artifact": {
                          "path": "org/example/processor/1.0.0/processor-1.0.0.jar"
                        }
                      }
                    },
                    {
                      "name": "cpw.mods:bootstraplauncher:2.0.2",
                      "downloads": {
                        "artifact": {
                          "path": "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar"
                        }
                      }
                    },
                    {
                      "name": "cpw.mods:securejarhandler:3.0.8",
                      "downloads": {
                        "artifact": {
                          "path": "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar"
                        }
                      }
                    },
                    {
                      "name": "net.neoforged.fancymodloader:loader:4.0.42",
                      "downloads": {
                        "artifact": {
                          "path": "net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar"
                        }
                      }
                    },
                    {
                      "name": "net.neoforged:neoforge:21.1.172:universal",
                      "downloads": {
                        "artifact": {
                          "path": "net/neoforged/neoforge/21.1.172/neoforge-21.1.172-universal.jar"
                        }
                      }
                    }
                  ]
                }
                """.formatted(neoFormVersion, processorCoordinate, File.pathSeparator);
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
