package dev.vfyjxf.gradle.launcher.core.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.vfyjxf.gradle.launcher.core.auth.AuthProvider;
import dev.vfyjxf.gradle.launcher.core.auth.OfflineAuthProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void defensivelyCopiesMutableCollections() {
        List<String> arguments = new ArrayList<>(List.of("--demo"));
        Map<String, String> environment = new HashMap<>(Map.of("ENV_ONE", "value"));

        LaunchCommand command = new LaunchCommand(
                Path.of("java-bin", "java"),
                arguments,
                Path.of("run-production", "client"),
                environment);

        arguments.add("--changed");
        environment.put("ENV_ONE", "changed");

        assertThat(command.arguments()).containsExactly("--demo");
        assertThat(command.environment()).containsEntry("ENV_ONE", "value");
        assertThatThrownBy(() -> command.arguments().add("--fullscreen"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.environment().put("ENV_TWO", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void treatsNullCollectionsAsEmptyImmutableCollections() {
        LaunchCommand command = new LaunchCommand(
                Path.of("java-bin", "java"),
                null,
                Path.of("run-production", "client"),
                null);

        assertThat(command.arguments()).isEmpty();
        assertThat(command.environment()).isEmpty();
        assertThatThrownBy(() -> command.arguments().add("--demo"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.environment().put("ENV_ONE", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void prepareWritesAcceptedEulaForServerRuns() throws Exception {
        LaunchSpec spec = serverSpec();

        new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .prepare(new LaunchContext(spec, false));

        assertThat(Files.readString(spec.paths().workingDir().resolve("eula.txt")))
                .contains("eula=true");
    }

    @Test
    void prepareStagesConfiguredModsIntoWorkingDirectoryModsFolder() throws Exception {
        Path modJar = tempDir.resolve("build/libs/example-mod.jar");
        Files.createDirectories(modJar.getParent());
        Files.writeString(modJar, "mod-content");
        LaunchSpec spec = clientSpecWithMods(List.of(new LaunchMod("gradle", "example-mod.jar", null, modJar, Map.of())));

        new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .prepare(new LaunchContext(spec, false));

        assertThat(spec.paths().workingDir().resolve("mods/example-mod.jar"))
                .hasContent("mod-content");
    }

    @Test
    void prepareRemovesStaleJarFilesFromWorkingDirectoryModsFolder() throws Exception {
        Path modJar = tempDir.resolve("build/libs/current-mod.jar");
        Files.createDirectories(modJar.getParent());
        Files.writeString(modJar, "current-content");
        LaunchSpec spec = clientSpecWithMods(List.of(new LaunchMod("gradle", "current-mod.jar", null, modJar, Map.of())));
        Path modsDir = spec.paths().workingDir().resolve("mods");
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("stale-mod.jar"), "stale-content");
        Files.writeString(modsDir.resolve("readme.txt"), "keep");

        new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .prepare(new LaunchContext(spec, false));

        assertThat(modsDir.resolve("current-mod.jar")).hasContent("current-content");
        assertThat(modsDir.resolve("stale-mod.jar")).doesNotExist();
        assertThat(modsDir.resolve("readme.txt")).hasContent("keep");
    }

    @Test
    void prepareRejectsConfiguredModsWithSameTargetFileName() throws Exception {
        Path firstModJar = tempDir.resolve("first/libs/duplicate-mod.jar");
        Path secondModJar = tempDir.resolve("second/libs/duplicate-mod.jar");
        Files.createDirectories(firstModJar.getParent());
        Files.createDirectories(secondModJar.getParent());
        Files.writeString(firstModJar, "first");
        Files.writeString(secondModJar, "second");
        LaunchSpec spec = clientSpecWithMods(List.of(
                new LaunchMod("gradle", "duplicate-mod.jar", null, firstModJar, Map.of()),
                new LaunchMod("gradle", "duplicate-mod.jar", null, secondModJar, Map.of())));

        LauncherEngine engine = new LauncherEngine(
                new LaunchSpecValidator(),
                new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(new StaticResolver())),
                new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner());

        assertThatThrownBy(() -> engine.prepare(new LaunchContext(spec, false)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate staged mod filename")
                .hasMessageContaining("duplicate-mod.jar");
    }

    @Test
    void commandAuthenticatesClientRunsBeforePreparingResolver() throws Exception {
        LaunchSpec spec = clientSpecWithMods(List.of());

        LaunchCommand command = new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new AuthAwareResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner(),
                        new OfflineAuthProvider())
                .command(new LaunchContext(spec, false));

        assertThat(command.arguments())
                .containsSubsequence("--username", "DevPlayer")
                .containsSubsequence("--accessToken", "0")
                .containsSubsequence("--userType", "msa");
    }

    @Test
    void commandWrapsMicrosoftClientRunsWithDevLogin() {
        LaunchSpec spec = microsoftClientSpec();
        Path gameJar = tempDir.resolve("libraries/game.jar");
        Path devLoginStorage = tempDir.resolve("gradle-home/caches/production-gradle/auth/devlogin");

        LaunchCommand command = new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .command(
                        new LaunchContext(spec, false),
                        new dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch(
                                List.of(gameJar),
                                List.of("-Xmx2G"),
                                List.of(
                                        "--username", "DevPlayer",
                                        "--uuid", "offline-uuid",
                                        "--accessToken", "offline-token",
                                        "--userType", "microsoft",
                                        "--demo"),
                                "example.GameMain"));

        assertThat(command.arguments())
                .contains("-Ddevlogin.storage=" + devLoginStorage)
                .contains("dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge")
                .containsSubsequence(
                        "dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge",
                        "--launch_target",
                        "example.GameMain")
                .contains("--demo")
                .doesNotContain("--username", "DevPlayer", "--uuid", "offline-uuid", "--accessToken", "offline-token",
                        "--userType", "microsoft");
        assertThat(command.arguments().get(command.arguments().indexOf("-cp") + 1))
                .contains(gameJar.toString());
    }

    @Test
    void commandMergesDevLoginJarIntoExistingClasspathArgument() {
        LaunchSpec spec = microsoftClientSpec();
        Path gameJar = tempDir.resolve("libraries/game.jar");

        LaunchCommand command = new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .command(
                        new LaunchContext(spec, false),
                        new dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch(
                                List.of(gameJar),
                                List.of("-cp", gameJar.toString()),
                                List.of(),
                                "example.GameMain"));

        assertThat(command.arguments())
                .contains("dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge")
                .containsSubsequence(
                        "dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge",
                        "--launch_target",
                        "example.GameMain");
        assertThat(command.arguments().get(command.arguments().indexOf("-cp") + 1))
                .contains(gameJar.toString())
                .contains("DevLogin");
    }

    @Test
    void commandStoresDevLoginAccountsUnderGradleUserHomeWhenCacheDirIsCustomized() {
        Path gradleUserHome = tempDir.resolve("gradle-home");
        Path customCacheDir = tempDir.resolve("project-cache");
        LaunchSpec spec = microsoftClientSpec(gradleUserHome, customCacheDir);

        LaunchCommand command = new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner())
                .command(
                        new LaunchContext(spec, false),
                        new dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch(
                                List.of(),
                                List.of(),
                                List.of(),
                                "example.GameMain"));

        assertThat(command.arguments())
                .contains("-Ddevlogin.storage=" + gradleUserHome.resolve("caches/production-gradle/auth/devlogin"))
                .doesNotContain("-Ddevlogin.storage=" + customCacheDir.resolve("auth/devlogin"));
    }

    @Test
    void commandDoesNotUseBuiltInAuthProviderForDevLoginRuns() throws Exception {
        LaunchSpec spec = microsoftClientSpec();

        LaunchCommand command = new LauncherEngine(
                        new LaunchSpecValidator(),
                        new dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry(List.of(
                                new StaticResolver())),
                        new dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner(),
                        request -> {
                            throw new AssertionError("Microsoft auth should be handled by DevLogin.");
                        })
                .command(new LaunchContext(spec, false));

        assertThat(command.arguments())
                .contains("dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge")
                .containsSubsequence(
                        "dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge",
                        "--launch_target",
                        "example.Main");
    }

    private LaunchSpec serverSpec() {
        Path instanceDir = tempDir.resolve("run-production/server");
        Path cacheDir = tempDir.resolve("cache");
        return new LaunchSpec(
                1,
                "server",
                "server",
                "1.21.1",
                "vanilla",
                null,
                new LaunchPaths(
                        instanceDir,
                        instanceDir,
                        cacheDir,
                        cacheDir.resolve("assets"),
                        cacheDir.resolve("libraries"),
                        instanceDir.resolve("natives"),
                        instanceDir.resolve("logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(),
                new LaunchSettings(List.of(), List.of(), Map.of(), null, true),
                Map.of(),
                Map.of("offline", false));
    }

    private LaunchSpec microsoftClientSpec() {
        return microsoftClientSpec(tempDir.resolve("gradle-home"), tempDir.resolve("cache"));
    }

    private LaunchSpec microsoftClientSpec(Path gradleUserHome, Path cacheDir) {
        Path instanceDir = tempDir.resolve("run-production/microsoft-client");
        return new LaunchSpec(
                1,
                "microsoftClient",
                "client",
                "1.21.1",
                "vanilla",
                null,
                new LaunchPaths(
                        instanceDir,
                        instanceDir,
                        cacheDir,
                        cacheDir.resolve("assets"),
                        cacheDir.resolve("libraries"),
                        instanceDir.resolve("natives"),
                        instanceDir.resolve("logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("microsoft", "DevPlayer", null),
                List.of(),
                new LaunchSettings(List.of(), List.of(), Map.of(), null, false),
                Map.of(),
                Map.of("offline", false, "userHome", gradleUserHome.toString()));
    }

    private LaunchSpec clientSpecWithMods(List<LaunchMod> mods) {
        Path instanceDir = tempDir.resolve("run-production/client");
        Path cacheDir = tempDir.resolve("cache");
        return new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "vanilla",
                null,
                new LaunchPaths(
                        instanceDir,
                        instanceDir,
                        cacheDir,
                        cacheDir.resolve("assets"),
                        cacheDir.resolve("libraries"),
                        instanceDir.resolve("natives"),
                        instanceDir.resolve("logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                mods,
                new LaunchSettings(List.of(), List.of(), Map.of(), null, false),
                Map.of(),
                Map.of("offline", false));
    }

    private static final class StaticResolver implements dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver {
        @Override
        public boolean supports(String loader) {
            return "vanilla".equals(loader);
        }

        @Override
        public dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch prepare(LaunchSpec spec) throws IOException {
            return new dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch(
                    List.of(),
                    List.of(),
                    List.of(),
                    "example.Main");
        }
    }

    private static final class AuthAwareResolver implements dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver {
        @Override
        public boolean supports(String loader) {
            return "vanilla".equals(loader);
        }

        @Override
        public dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch prepare(LaunchSpec spec) {
            throw new AssertionError("LauncherEngine should pass LaunchContext to resolvers.");
        }

        @Override
        public dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch prepare(LaunchContext context) {
            AuthProvider.AuthResult authResult = context.authResult();
            assertThat(authResult).isNotNull();
            assertThat(authResult.status()).isEqualTo(AuthProvider.AuthStatus.AUTHENTICATED);
            return new dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch(
                    List.of(),
                    List.of(),
                    authResult.gameArguments(),
                    "example.Main");
        }
    }
}
