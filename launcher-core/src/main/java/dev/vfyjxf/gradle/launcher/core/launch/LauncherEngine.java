package dev.vfyjxf.gradle.launcher.core.launch;

import dev.vfyjxf.gradle.launcher.core.auth.AuthProvider;
import dev.vfyjxf.gradle.launcher.core.auth.DevLoginLaunchBridge;
import dev.vfyjxf.gradle.launcher.core.auth.OfflineAuthProvider;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolverRegistry;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.fabric.FabricResolver;
import dev.vfyjxf.gradle.launcher.core.loader.forge.ForgeResolver;
import dev.vfyjxf.gradle.launcher.core.loader.neoforge.NeoForgeResolver;
import dev.vfyjxf.gradle.launcher.core.loader.vanilla.VanillaResolver;
import dev.vfyjxf.gradle.launcher.core.process.GameProcessRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class LauncherEngine {
    private static final String DEVLOGIN_BRIDGE_MAIN_CLASS = DevLoginLaunchBridge.class.getName();
    private static final String DEVLOGIN_MAIN_CLASS = "net.covers1624.devlogin.DevLogin";
    private static final String DEVLOGIN_STORAGE_PROPERTY = "-Ddevlogin.storage=";
    private static final String DEVLOGIN_LAUNCH_TARGET_ARGUMENT = "--launch_target";
    private static final Set<String> AUTH_ARGUMENTS = Set.of(
            "--username",
            "--uuid",
            "--accessToken",
            "--access-token",
            "--access_token",
            "--userType",
            "--user-type",
            "--user_type");

    private final LaunchSpecValidator validator;
    private final LoaderResolverRegistry resolverRegistry;
    private final GameProcessRunner processRunner;
    private final AuthProvider offlineAuthProvider;

    public LauncherEngine() {
        this(
                new LaunchSpecValidator(),
                new LoaderResolverRegistry(List.of(
                        new VanillaResolver(),
                        new FabricResolver(),
                        new ForgeResolver(),
                        new NeoForgeResolver())),
                new GameProcessRunner(),
                new OfflineAuthProvider());
    }

    public LauncherEngine(
            LaunchSpecValidator validator,
            LoaderResolverRegistry resolverRegistry,
            GameProcessRunner processRunner) {
        this(validator, resolverRegistry, processRunner, new OfflineAuthProvider());
    }

    public LauncherEngine(
            LaunchSpecValidator validator,
            LoaderResolverRegistry resolverRegistry,
            GameProcessRunner processRunner,
            AuthProvider offlineAuthProvider) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.resolverRegistry = Objects.requireNonNull(resolverRegistry, "resolverRegistry");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.offlineAuthProvider = Objects.requireNonNull(offlineAuthProvider, "offlineAuthProvider");
    }

    public void validate(LaunchContext context) {
        validator.validate(context.spec());
    }

    public PreparedLaunch prepare(LaunchContext context) throws Exception {
        validate(context);
        createLaunchDirectories(context.spec().paths());
        writeEulaIfAccepted(context.spec());
        stageMods(context.spec());
        LaunchContext authenticatedContext = authenticate(context);
        return resolverRegistry.resolverFor(authenticatedContext.spec().loader()).prepare(authenticatedContext);
    }

    public LaunchCommand command(LaunchContext context) throws Exception {
        return command(context, prepare(context));
    }

    public LaunchCommand command(LaunchContext context, PreparedLaunch preparedLaunch) {
        validate(context);
        PreparedLaunch launch = devLoginLaunch(context.spec(), preparedLaunch);
        List<String> arguments = new ArrayList<>(launch.jvmArgs());
        int classpathArgumentIndex = classpathArgumentIndex(arguments);
        if (classpathArgumentIndex >= 0) {
            mergeClasspathArgument(arguments, classpathArgumentIndex, launch.classpath());
        } else if (!launch.classpath().isEmpty()) {
            arguments.add("-cp");
            arguments.add(classpath(launch.classpath()));
        }
        if (text(launch.mainClass())) {
            arguments.add(launch.mainClass());
        }
        arguments.addAll(launch.gameArgs());

        LaunchSpec spec = context.spec();
        return new LaunchCommand(
                javaExecutable(spec),
                arguments,
                spec.paths().workingDir(),
                environment(spec));
    }

    public int launch(LaunchContext context) throws Exception {
        LaunchCommand command = command(context);
        System.out.println("[ProductionGradle] Launching game process.");
        return processRunner.run(command);
    }

    private LaunchContext authenticate(LaunchContext context) throws IOException {
        if (!"client".equals(context.spec().type()) || context.authResult() != null) {
            return context;
        }
        if ("microsoft".equals(context.spec().auth().mode())) {
            return context;
        }
        AuthProvider.AuthResult result = offlineAuthProvider.authenticate(new AuthProvider.AuthRequest(
                context.spec().paths().cacheDir().getParent().getParent(),
                context.spec().auth().userName(),
                context.spec().auth().tokenCacheKey()));
        if (result.status() != AuthProvider.AuthStatus.AUTHENTICATED) {
            throw new IOException("authentication is pending; complete the device-code flow and retry");
        }
        return context.withAuthResult(result);
    }

    private static PreparedLaunch devLoginLaunch(LaunchSpec spec, PreparedLaunch launch) {
        if (spec.auth() == null || !"microsoft".equals(spec.auth().mode())) {
            return launch;
        }
        List<Path> classpath = new ArrayList<>(launch.classpath());
        addCodeSource(classpath, DEVLOGIN_BRIDGE_MAIN_CLASS);
        addCodeSource(classpath, DEVLOGIN_MAIN_CLASS);

        List<String> jvmArgs = new ArrayList<>(launch.jvmArgs());
        jvmArgs.add(DEVLOGIN_STORAGE_PROPERTY + devLoginStorage(spec));

        List<String> gameArgs = new ArrayList<>();
        gameArgs.add(DEVLOGIN_LAUNCH_TARGET_ARGUMENT);
        gameArgs.add(launch.mainClass());
        gameArgs.addAll(withoutAuthArguments(launch.gameArgs()));

        return new PreparedLaunch(classpath, jvmArgs, gameArgs, DEVLOGIN_BRIDGE_MAIN_CLASS);
    }

    private static Path devLoginStorage(LaunchSpec spec) {
        return gradleUserHome(spec).resolve("caches").resolve("production-gradle").resolve("auth").resolve("devlogin");
    }

    private static Path gradleUserHome(LaunchSpec spec) {
        if (spec != null && spec.gradle() != null) {
            Object value = spec.gradle().get("userHome");
            if (value instanceof String path && !path.isBlank()) {
                return Path.of(path);
            }
        }
        return Path.of(System.getProperty("user.home"), ".gradle");
    }

    private static List<String> withoutAuthArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>(arguments.size());
        boolean skipNext = false;
        for (String argument : arguments) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (isAuthArgument(argument)) {
                skipNext = true;
                continue;
            }
            filtered.add(argument);
        }
        return filtered;
    }

    private static boolean isAuthArgument(String argument) {
        return AUTH_ARGUMENTS.contains(argument);
    }

    private static void addCodeSource(List<Path> classpath, String className) {
        try {
            Class<?> type = Class.forName(className, false, LauncherEngine.class.getClassLoader());
            if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) {
                return;
            }
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!classpath.contains(path)) {
                classpath.add(path);
            }
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("DevLogin is not available on the launcher classpath: " + className, exception);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve DevLogin code source", exception);
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void createLaunchDirectories(LaunchPaths paths) throws IOException {
        createDirectory(paths.instanceDir());
        createDirectory(paths.workingDir());
        createDirectory(paths.nativesDir());
        createDirectory(paths.logsDir());
    }

    private static void createDirectory(Path path) throws IOException {
        if (path != null) {
            Files.createDirectories(path);
        }
    }

    private static void writeEulaIfAccepted(LaunchSpec spec) throws IOException {
        if (!"server".equals(spec.type()) || spec.launch() == null || !spec.launch().eulaAccepted()) {
            return;
        }
        Path workingDir = spec.paths() == null ? null : spec.paths().workingDir();
        if (workingDir == null) {
            return;
        }
        Files.createDirectories(workingDir);
        Files.writeString(
                workingDir.resolve("eula.txt"),
                "# Generated by ProductionGradle because production.runs." + spec.runName()
                        + ".eula is accepted." + System.lineSeparator()
                        + "eula=true" + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static void stageMods(LaunchSpec spec) throws IOException {
        Path workingDir = spec.paths() == null ? null : spec.paths().workingDir();
        if (workingDir == null) {
            return;
        }
        Path modsDir = workingDir.resolve("mods");
        Files.createDirectories(modsDir);
        Map<String, Path> stagedMods = stagedModsByFileName(spec.mods());
        removeStaleModJars(modsDir, stagedMods.keySet());
        if (stagedMods.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Path> stagedMod : stagedMods.entrySet()) {
            Files.copy(stagedMod.getValue(), modsDir.resolve(stagedMod.getKey()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Path> stagedModsByFileName(List<LaunchMod> mods) throws IOException {
        Map<String, Path> stagedMods = new LinkedHashMap<>();
        for (LaunchMod mod : mods) {
            if (mod.file() == null || !Files.isRegularFile(mod.file())) {
                throw new IOException("mod file does not exist: " + mod.file());
            }
            Path source = mod.file().toAbsolutePath().normalize();
            String fileName = source.getFileName().toString();
            Path previous = stagedMods.putIfAbsent(fileName, source);
            if (previous != null && !previous.equals(source)) {
                throw new IOException("duplicate staged mod filename '" + fileName
                        + "' from " + previous + " and " + source);
            }
        }
        return stagedMods;
    }

    private static void removeStaleModJars(Path modsDir, Set<String> currentFileNames) throws IOException {
        try (var entries = Files.list(modsDir)) {
            Set<String> current = new HashSet<>(currentFileNames);
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)
                        && entry.getFileName().toString().endsWith(".jar")
                        && !current.contains(entry.getFileName().toString())) {
                    Files.delete(entry);
                }
            }
        }
    }

    private static Path javaExecutable(LaunchSpec spec) {
        if (spec.java() != null && spec.java().executable() != null) {
            return spec.java().executable();
        }
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    private static Map<String, String> environment(LaunchSpec spec) {
        if (spec.launch() == null) {
            return Map.of();
        }
        return spec.launch().environment();
    }

    private static int classpathArgumentIndex(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if ("-cp".equals(argument) || "-classpath".equals(argument) || "--class-path".equals(argument)) {
                return index;
            }
        }
        return -1;
    }

    private static void mergeClasspathArgument(List<String> arguments, int argumentIndex, List<Path> classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return;
        }
        int valueIndex = argumentIndex + 1;
        String existing = valueIndex < arguments.size() ? arguments.get(valueIndex) : "";
        String merged = mergeClasspath(existing, classpath);
        if (valueIndex < arguments.size()) {
            arguments.set(valueIndex, merged);
        } else {
            arguments.add(merged);
        }
    }

    private static String classpath(List<Path> classpath) {
        List<String> entries = classpath.stream()
                .map(Path::toString)
                .toList();
        return String.join(File.pathSeparator, entries);
    }

    private static String mergeClasspath(String existing, List<Path> classpath) {
        List<String> entries = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            entries.addAll(List.of(existing.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        }
        for (Path entry : classpath) {
            String value = entry.toString();
            if (!entries.contains(value)) {
                entries.add(value);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

}
