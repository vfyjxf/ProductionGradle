package dev.vfyjxf.gradle.launcher.core.loader;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchAuth;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchJava;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchPaths;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSettings;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ProductionResolverTestFixtures {
    public static final String MINECRAFT_VERSION = "1.21.1";

    private ProductionResolverTestFixtures() {
    }

    public static LoaderResolver resolver(String className) {
        try {
            return (LoaderResolver) Class.forName(className).getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("resolver class must exist: " + className, exception);
        }
    }

    public static LaunchSpec spec(Path tempDir, String loader, String loaderVersion, String type) {
        return spec(tempDir, loader, loaderVersion, type, Map.of(), Map.of());
    }

    public static LaunchSpec spec(
            Path tempDir,
            String loader,
            String loaderVersion,
            String type,
            Map<String, Object> resolutionHints,
            Map<String, Object> gradle) {
        Path cache = tempDir.resolve("cache");
        Path instance = tempDir.resolve("run-" + loader + "-" + type);
        return new LaunchSpec(
                1,
                loader + "-" + type,
                type,
                MINECRAFT_VERSION,
                loader,
                loaderVersion,
                new LaunchPaths(
                        instance,
                        instance,
                        cache,
                        cache.resolve("assets"),
                        cache.resolve("libraries"),
                        instance.resolve("natives"),
                        instance.resolve("logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "LoaderPlayer", null),
                List.of(),
                new LaunchSettings(List.of(), List.of(), Map.of(), null, "server".equals(type)),
                resolutionHints,
                gradle);
    }

    public static Path versionDirectory(LaunchSpec spec) {
        return spec.paths().cacheDir()
                .resolve("minecraft")
                .resolve("versions")
                .resolve(MINECRAFT_VERSION);
    }

    public static Path writeFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "cached");
        return path;
    }

    public static Path writeOfficialLibrary(LaunchSpec spec) throws IOException {
        return writeFile(spec.paths().cacheDir()
                .resolve("libraries")
                .resolve("org/example/minecraft-lib/1.0.0/minecraft-lib-1.0.0.jar"));
    }

    public static Path writeMinecraftClientJar(LaunchSpec spec) throws IOException {
        return writeFile(versionDirectory(spec).resolve(MINECRAFT_VERSION + ".jar"));
    }

    public static Path writeMinecraftServerJar(LaunchSpec spec) throws IOException {
        return writeFile(versionDirectory(spec).resolve(MINECRAFT_VERSION + "-server.jar"));
    }

    public static void writeMinecraftVersionJson(LaunchSpec spec) throws IOException {
        Path versionDirectory = versionDirectory(spec);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(MINECRAFT_VERSION + ".json"), """
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
                    },
                    "server": {
                      "sha1": "server-sha1",
                      "size": 1,
                      "url": "https://example.invalid/server.jar"
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
                    "jvm": [
                      "-Djava.library.path=${natives_directory}"
                    ],
                    "game": [
                      "--username",
                      "${auth_player_name}",
                      "--uuid",
                      "${auth_uuid}",
                      "--accessToken",
                      "${auth_access_token}",
                      "--userType",
                      "${user_type}",
                      "--gameDir",
                      "${game_directory}",
                      "--assetsDir",
                      "${assets_root}",
                      "--assetIndex",
                      "${assets_index_name}",
                      "--versionType",
                      "${version_type}"
                    ]
                  }
                }
                """);
    }

    public static String joinedClasspath(PreparedLaunch launch) {
        return launch.classpath().stream()
                .map(Path::toString)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + System.lineSeparator() + right);
    }
}
