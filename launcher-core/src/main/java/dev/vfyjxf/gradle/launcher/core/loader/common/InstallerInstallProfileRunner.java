package dev.vfyjxf.gradle.launcher.core.loader.common;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.download.OfflineCacheMissException;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class InstallerInstallProfileRunner {
    private static final List<String> REQUIRED_DATA_KEYS = List.of("PATCHED", "MC_SRG", "MC_EXTRA");

    private final ProductionLaunchSupport support;
    private final String displayName;

    public InstallerInstallProfileRunner(ProductionLaunchSupport support, String displayName) {
        this.support = support;
        this.displayName = displayName;
    }

    public void install(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI mavenBaseUri) throws Exception {
        install(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                loaderVersionPath,
                installerPath,
                installerCoordinate,
                mavenBaseUri,
                true);
    }

    public void install(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI mavenBaseUri,
            boolean runIfProfileIsPresent) throws Exception {
        Path installProfilePath = loaderVersionPath.getParent().resolve("install_profile.json");
        if (!runIfProfileIsPresent && !hasGeneratedLoaderArtifact(loaderVersion)) {
            return;
        }
        if (!ensureInstallProfile(context, loaderVersionPath, installerPath, installerCoordinate, mavenBaseUri, installProfilePath)) {
            return;
        }

        InstallerInstallProfile profile = ProductionLaunchSupport.read(
                installProfilePath,
                InstallerInstallProfile.class,
                displayName + " install profile");
        support.resolveLibraries(
                cache,
                profile.libraries(),
                installProfilePath,
                displayName + " installer library",
                mavenBaseUri,
                context);

        List<Path> requiredArtifacts = requiredProductionArtifacts(context.spec(), cache, profile, installerCoordinate);
        if (allRegularFiles(requiredArtifacts)) {
            return;
        }
        if (context.offline()) {
            throw new IllegalStateException("Offline cache miss: " + displayName
                    + " installed production artifacts are missing. Run once without Gradle --offline to populate "
                    + cache.root());
        }

        Map<String, String> placeholders = placeholders(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                profile,
                installerPath,
                installProfilePath);
        for (InstallerInstallProfile.Processor processor : profile.processors()) {
            if (!processor.appliesTo(context.spec().type())) {
                continue;
            }
            runProcessor(context, cache, installProfilePath, processor, mavenBaseUri, placeholders);
        }
        requireGeneratedArtifacts(requiredArtifacts, installProfilePath);
    }

    public Path serverArgumentsPath(CacheLayout cache, String installerCoordinate) {
        String fileName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "win_args.txt"
                : "unix_args.txt";
        Path installerArtifactPath = Path.of(support.mavenArtifactPath(installerCoordinate, null));
        Path installerDirectory = installerArtifactPath.getParent();
        if (installerDirectory == null) {
            throw new IllegalStateException(displayName + " installer coordinate has no artifact directory: "
                    + installerCoordinate);
        }
        return cache.libraries().resolve(installerDirectory).resolve(fileName);
    }

    public List<String> resolveServerArguments(CacheLayout cache, String installerCoordinate) throws IOException {
        Path argumentsPath = serverArgumentsPath(cache, installerCoordinate);
        if (!Files.isRegularFile(argumentsPath)) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (String rawLine : Files.readAllLines(argumentsPath, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            for (String argument : splitArguments(line)) {
                arguments.add(resolveServerArgument(cache, argument));
            }
        }
        return arguments;
    }

    private boolean ensureInstallProfile(
            LaunchContext context,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI mavenBaseUri,
            Path installProfilePath) throws IOException {
        if (Files.isRegularFile(installProfilePath)) {
            return true;
        }
        if (!Files.isRegularFile(installerPath)) {
            URI installerUri = mavenBaseUri.resolve(support.mavenArtifactPath(installerCoordinate, loaderVersionPath));
            if (context.offline()) {
                throw new IllegalStateException(new OfflineCacheMissException(installerUri, installerPath).getMessage());
            }
            support.resolveDownload(installerPath, displayName + " installer", installerUri, context);
        }
        extractInstallerEntry(installerPath, "install_profile.json", installProfilePath, false);
        return Files.isRegularFile(installProfilePath);
    }

    private List<Path> requiredProductionArtifacts(
            LaunchSpec spec,
            CacheLayout cache,
            InstallerInstallProfile profile,
            String installerCoordinate) {
        List<Path> artifacts = new ArrayList<>();
        for (String key : REQUIRED_DATA_KEYS) {
            addDataArtifact(artifacts, cache, profile, key, spec.type());
        }
        if ("server".equals(spec.type()) && profileReferencesServerArgs(profile)) {
            artifacts.add(serverArgumentsPath(cache, installerCoordinate));
        }
        return artifacts;
    }

    private boolean profileReferencesServerArgs(InstallerInstallProfile profile) {
        for (InstallerInstallProfile.Processor processor : profile.processors()) {
            for (String argument : processor.args()) {
                if (argument.contains("unix_args.txt") || argument.contains("win_args.txt")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasGeneratedLoaderArtifact(VersionJson loaderVersion) {
        for (VersionJson.Library library : loaderVersion.libraries()) {
            VersionJson.Artifact artifact = library.downloads() == null ? null : library.downloads().artifact();
            if (artifact != null
                    && ProductionLaunchSupport.text(artifact.path())
                    && !ProductionLaunchSupport.text(artifact.url())) {
                return true;
            }
        }
        return false;
    }

    private void addDataArtifact(
            List<Path> artifacts,
            CacheLayout cache,
            InstallerInstallProfile profile,
            String key,
            String side) {
        String value = profile.dataValue(key, side);
        if (isCoordinateReference(value)) {
            artifacts.add(cache.libraries().resolve(support.mavenArtifactPath(stripCoordinateReference(value), null)));
        }
    }

    private Map<String, String> placeholders(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            InstallerInstallProfile profile,
            Path installerPath,
            Path installProfilePath) throws IOException {
        LaunchSpec spec = context.spec();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("INSTALLER", installerPath.toString());
        placeholders.put("ROOT", cache.root().toString());
        placeholders.put("LIBRARY_DIR", cache.libraries().toString());
        placeholders.put("MINECRAFT_JAR", support.minecraftJar(spec, cache, minecraftVersion).toString());
        placeholders.put("MINECRAFT_VERSION", spec.minecraftVersion());
        placeholders.put("SIDE", spec.type());
        placeholders.put("VERSION_JSON", loaderVersion.id() == null ? "" : loaderVersion.id());
        placeholders.put("FORGE_VERSION", spec.loaderVersion());
        placeholders.put("NEOFORGE_VERSION", spec.loaderVersion());
        for (String key : profile.data().keySet()) {
            String value = profile.dataValue(key, spec.type());
            if (value != null) {
                placeholders.put(key, resolveProfileValue(value, cache, installerPath, installProfilePath));
            }
        }
        return placeholders;
    }

    private String resolveProfileValue(
            String value,
            CacheLayout cache,
            Path installerPath,
            Path installProfilePath) throws IOException {
        if (isCoordinateReference(value)) {
            return cache.libraries().resolve(support.mavenArtifactPath(stripCoordinateReference(value), installProfilePath))
                    .toString();
        }
        if (value.startsWith("/") && value.length() > 1) {
            Path extracted = installProfilePath.getParent().resolve(value.substring(1));
            extractInstallerEntry(installerPath, value.substring(1), extracted, true);
            return extracted.toString();
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void runProcessor(
            LaunchContext context,
            CacheLayout cache,
            Path installProfilePath,
            InstallerInstallProfile.Processor processor,
            URI mavenBaseUri,
            Map<String, String> placeholders) throws Exception {
        List<Path> classpath = processorClasspath(context, cache, installProfilePath, processor, mavenBaseUri);
        Path processorJar = processorJar(cache, installProfilePath, processor, mavenBaseUri, context);
        if (!classpath.contains(processorJar)) {
            classpath.add(processorJar);
        }
        if (classpath.isEmpty()) {
            throw new IllegalStateException(displayName + " installer processor has an empty classpath in "
                    + installProfilePath);
        }
        String mainClass = mainClass(processorJar);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable(context.spec()).toString());
        command.add("-cp");
        command.add(classpath(classpath));
        command.add(mainClass);
        for (String argument : processor.args()) {
            command.add(resolveArgument(interpolate(argument, placeholders), cache, installProfilePath));
        }

        System.out.println("[ProductionGradle] Running " + displayName + " installer processor " + processor.jar());
        Process process = new ProcessBuilder(command)
                .directory(cache.root().toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().transferTo(System.out);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(displayName + " installer processor failed with exit code " + exitCode
                    + ": " + processor.jar());
        }
    }

    private List<Path> processorClasspath(
            LaunchContext context,
            CacheLayout cache,
            Path installProfilePath,
            InstallerInstallProfile.Processor processor,
            URI mavenBaseUri) {
        List<String> coordinates = processor.classpath().isEmpty()
                ? List.of(processor.jar())
                : processor.classpath();
        List<Path> classpath = new ArrayList<>();
        for (String coordinate : coordinates) {
            classpath.add(ensureProcessorArtifact(context, cache, installProfilePath, coordinate, mavenBaseUri));
        }
        return classpath;
    }

    private Path processorJar(
            CacheLayout cache,
            Path installProfilePath,
            InstallerInstallProfile.Processor processor,
            URI mavenBaseUri,
            LaunchContext context) {
        return ensureProcessorArtifact(context, cache, installProfilePath, processor.jar(), mavenBaseUri);
    }

    private Path ensureProcessorArtifact(
            LaunchContext context,
            CacheLayout cache,
            Path installProfilePath,
            String coordinate,
            URI mavenBaseUri) {
        Path path = cache.libraries().resolve(support.mavenArtifactPath(coordinate, installProfilePath));
        if (!Files.isRegularFile(path)) {
            support.resolveMavenArtifact(
                    cache,
                    coordinate,
                    installProfilePath,
                    displayName + " installer processor library",
                    mavenBaseUri,
                    context);
        }
        return path;
    }

    private String resolveArgument(String argument, CacheLayout cache, Path installProfilePath) {
        if (isCoordinateReference(argument)) {
            return cache.libraries().resolve(support.mavenArtifactPath(stripCoordinateReference(argument), installProfilePath))
                    .toString();
        }
        return argument;
    }

    private static List<String> splitArguments(String line) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(character) && !singleQuoted && !doubleQuoted) {
                if (current.length() > 0) {
                    arguments.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
        }
        if (escaped) {
            current.append('\\');
        }
        if (current.length() > 0) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    private static String resolveServerArgument(CacheLayout cache, String argument) {
        if (argument.equals("libraries")) {
            return cache.libraries().toString();
        }
        if (argument.contains("libraries/") || argument.contains("libraries\\")) {
            return argument
                    .replace("libraries/", cache.libraries().toString() + File.separator)
                    .replace("libraries\\", cache.libraries().toString() + File.separator);
        }
        if (argument.endsWith("=libraries")) {
            return argument.substring(0, argument.length() - "libraries".length()) + cache.libraries();
        }
        return argument;
    }

    private static String mainClass(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            if (jarFile.getManifest() == null) {
                throw new IllegalStateException("installer processor is missing a manifest: " + jar);
            }
            String mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (!ProductionLaunchSupport.text(mainClass)) {
                throw new IllegalStateException("installer processor is missing Main-Class: " + jar);
            }
            return mainClass;
        }
    }

    private static Path javaExecutable(LaunchSpec spec) {
        if (spec.java() != null && spec.java().executable() != null) {
            return spec.java().executable();
        }
        String executableName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    private static String interpolate(String argument, Map<String, String> placeholders) {
        String interpolated = argument;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            interpolated = interpolated.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return interpolated;
    }

    private static boolean isCoordinateReference(String value) {
        return value != null && value.startsWith("[") && value.endsWith("]");
    }

    private static String stripCoordinateReference(String value) {
        return value.substring(1, value.length() - 1);
    }

    private static boolean allRegularFiles(List<Path> paths) {
        if (paths.isEmpty()) {
            return true;
        }
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                return false;
            }
        }
        return true;
    }

    private static void requireGeneratedArtifacts(List<Path> artifacts, Path installProfilePath) {
        for (Path artifact : artifacts) {
            if (!Files.isRegularFile(artifact)) {
                throw new IllegalStateException("Install profile did not produce required artifact "
                        + artifact + " from " + installProfilePath);
            }
        }
    }

    private static void extractInstallerEntry(
            Path installerPath,
            String entryName,
            Path target,
            boolean required) throws IOException {
        if (Files.isRegularFile(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream input = Files.newInputStream(installerPath);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entryName.equals(entry.getName())) {
                    continue;
                }
                Path normalizedTarget = target.normalize();
                Path parent = target.getParent();
                if (parent == null || !normalizedTarget.startsWith(parent.normalize())) {
                    throw new IOException("installer entry escapes target directory: " + entryName);
                }
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        if (required) {
            throw new IllegalStateException("Installer is missing " + entryName + ": " + installerPath);
        }
    }

    private static String classpath(List<Path> classpath) {
        List<String> entries = classpath.stream()
                .map(Path::toString)
                .toList();
        return String.join(File.pathSeparator, entries);
    }
}
