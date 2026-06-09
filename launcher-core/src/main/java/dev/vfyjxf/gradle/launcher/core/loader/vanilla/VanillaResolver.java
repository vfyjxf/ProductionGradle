package dev.vfyjxf.gradle.launcher.core.loader.vanilla;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.download.DownloadRequest;
import dev.vfyjxf.gradle.launcher.core.download.Downloader;
import dev.vfyjxf.gradle.launcher.core.download.OfflineCacheMissException;
import dev.vfyjxf.gradle.launcher.core.auth.AuthProvider;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidator;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.minecraft.LibraryRuleEvaluator;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;
import dev.vfyjxf.gradle.launcher.core.loader.common.MinecraftAssetDownloader;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;

public class VanillaResolver implements LoaderResolver {
    private static final URI DEFAULT_VERSION_MANIFEST_URI = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final URI DEFAULT_ASSET_BASE_URI = URI.create("https://resources.download.minecraft.net/");

    private final LaunchSpecValidator validator;
    private final LibraryRuleEvaluator ruleEvaluator;
    private final Downloader downloader;
    private final MinecraftAssetDownloader assetDownloader;
    private final URI versionManifestUri;
    private final URI assetBaseUri;

    public VanillaResolver() {
        this(new LaunchSpecValidator(), LibraryRuleEvaluator.current());
    }

    VanillaResolver(LaunchSpecValidator validator, LibraryRuleEvaluator ruleEvaluator) {
        this(validator, ruleEvaluator, DEFAULT_VERSION_MANIFEST_URI);
    }

    VanillaResolver(LaunchSpecValidator validator, LibraryRuleEvaluator ruleEvaluator, URI versionManifestUri) {
        this(validator, ruleEvaluator, versionManifestUri, DEFAULT_ASSET_BASE_URI, new Downloader());
    }

    VanillaResolver(
            LaunchSpecValidator validator,
            LibraryRuleEvaluator ruleEvaluator,
            URI versionManifestUri,
            URI assetBaseUri,
            Downloader downloader) {
        this.validator = validator;
        this.ruleEvaluator = ruleEvaluator;
        this.versionManifestUri = versionManifestUri;
        this.assetBaseUri = assetBaseUri;
        this.downloader = downloader;
        this.assetDownloader = new MinecraftAssetDownloader(downloader);
    }

    @Override
    public boolean supports(String loader) {
        return "vanilla".equals(loader);
    }

    @Override
    public PreparedLaunch prepare(LaunchSpec spec) throws Exception {
        return prepare(new LaunchContext(spec, offline(spec)));
    }

    @Override
    public PreparedLaunch prepare(LaunchContext context) throws Exception {
        LaunchSpec spec = context.spec();
        validator.validate(spec);
        if (!supports(spec.loader())) {
            throw new IllegalArgumentException("unsupported loader for vanilla resolver: " + spec.loader());
        }

        CacheLayout cache = cacheLayout(spec);
        Path versionDirectory = versionDirectory(cache, spec.minecraftVersion());
        Path versionJsonPath = versionDirectory.resolve(spec.minecraftVersion() + ".json");
        ensureMinecraftVersionMetadata(versionJsonPath, context);

        VersionJson version = VersionJson.read(versionJsonPath);
        Path minecraftJar = minecraftJarPath(cache, versionDirectory, spec.type(), spec.minecraftVersion(), version);
        ensureArtifact(minecraftJar, "Minecraft " + spec.type() + " jar", minecraftArtifact(version, spec.type()), context);
        if ("server".equals(spec.type())) {
            return prepareServer(context, cache, minecraftJar);
        }

        ensureAssets(cache, version, context);
        List<Path> classpath = new ArrayList<>();
        for (VersionJson.Library library : version.libraries()) {
            if (!ruleEvaluator.allowed(library.rules())) {
                continue;
            }
            libraryPath(cache, versionJsonPath, library).ifPresent(libraryPath -> {
                ensureArtifact(libraryPath, "Minecraft library", libraryArtifact(library), context);
                classpath.add(libraryPath);
            });
            nativeLibraryPath(cache, library).ifPresent(nativePath -> {
                ensureArtifact(nativePath, "Minecraft native library", nativeArtifact(library), context);
                extractNativeLibrary(nativePath, spec.paths().nativesDir());
                classpath.add(nativePath);
            });
        }

        classpath.add(minecraftJar);

        Map<String, String> placeholders = placeholders(context, cache, version, classpath);
        List<String> jvmArgs = new ArrayList<>(interpolate(metadataJvmArgs(version), placeholders));
        jvmArgs.addAll(interpolate(spec.launch().jvmArgs(), placeholders));
        List<String> gameArgs = new ArrayList<>(interpolate(metadataGameArgs(version), placeholders));
        gameArgs.addAll(interpolate(spec.launch().gameArgs(), placeholders));

        return new PreparedLaunch(classpath, jvmArgs, gameArgs, clientMainClass(spec, version, versionJsonPath));
    }

    private void ensureMinecraftVersionMetadata(Path versionJsonPath, LaunchContext context) throws Exception {
        if (Files.isRegularFile(versionJsonPath)) {
            return;
        }
        URI manifestUri = ProductionLaunchSupport.uriHint(
                context.spec(),
                "minecraftVersionManifestUri",
                versionManifestUri);
        if (context.offline()) {
            throw new IllegalStateException(
                    new OfflineCacheMissException(manifestUri, versionJsonPath).getMessage());
        }
        VersionManifest manifest = Json.mapper().readValue(manifestUri.toURL(), VersionManifest.class);
        VersionManifest.Version version = manifest.find(context.spec().minecraftVersion())
                .orElseThrow(() -> new IllegalStateException("Minecraft version " + context.spec().minecraftVersion()
                        + " not found in " + manifestUri));
        ensureDownload(versionJsonPath, "Minecraft version metadata", version.url(), "SHA-1", version.sha1(), context);
    }

    private void ensureArtifact(
            Path path,
            String description,
            VersionJson.Artifact artifact,
            LaunchContext context) {
        if (Files.isRegularFile(path)) {
            return;
        }
        if (artifact == null || !text(artifact.url())) {
            requireCachedFile(path, description);
            return;
        }
        ensureDownload(path, description, URI.create(artifact.url()), "SHA-1", artifact.sha1(), context);
    }

    private void ensureDownload(
            Path target,
            String description,
            URI uri,
            String checksumAlgorithm,
            String checksum,
            LaunchContext context) {
        try {
            downloader.download(new DownloadRequest(uri, target, checksumAlgorithm, checksum, context.offline()));
        } catch (OfflineCacheMissException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to resolve " + description.toLowerCase(Locale.ROOT)
                    + " at " + target, exception);
        }
    }

    private static VersionJson.Artifact minecraftArtifact(VersionJson version, String type) {
        if (version.downloads() == null) {
            return null;
        }
        if ("server".equals(type)) {
            return version.downloads().server();
        }
        return version.downloads().client();
    }

    private static VersionJson.Artifact libraryArtifact(VersionJson.Library library) {
        VersionJson.LibraryDownloads downloads = library.downloads();
        return downloads == null ? null : downloads.artifact();
    }

    private static VersionJson.Artifact nativeArtifact(VersionJson.Library library) {
        VersionJson.LibraryDownloads downloads = library.downloads();
        String classifier = nativeClassifier(library);
        if (downloads == null || classifier == null) {
            return null;
        }
        return downloads.classifiers().get(classifier);
    }

    private PreparedLaunch prepareServer(LaunchContext context, CacheLayout cache, Path serverJar) throws Exception {
        LaunchSpec spec = context.spec();
        List<Path> classpath = List.of(serverJar);
        Map<String, String> placeholders = placeholders(context, cache, null, classpath);
        return new PreparedLaunch(
                classpath,
                interpolate(spec.launch().jvmArgs(), placeholders),
                interpolate(spec.launch().gameArgs(), placeholders),
                serverMainClass(spec, serverJar));
    }

    private static CacheLayout cacheLayout(LaunchSpec spec) {
        Path cacheDir = spec.paths().cacheDir();
        if (cacheDir == null) {
            throw new IllegalArgumentException("paths.cacheDir is required");
        }
        return CacheLayout.under(cacheDir);
    }

    private static Path versionDirectory(CacheLayout cache, String minecraftVersion) {
        return cache.minecraft().resolve("versions").resolve(minecraftVersion);
    }

    private Path minecraftJarPath(
            CacheLayout cache,
            Path versionDirectory,
            String launchType,
            String minecraftVersion,
            VersionJson version) {
        if ("server".equals(launchType)) {
            return artifactPath(cache.minecraft(), version.downloads() == null ? null : version.downloads().server())
                    .orElse(versionDirectory.resolve(minecraftVersion + "-server.jar"));
        }
        return artifactPath(cache.minecraft(), version.downloads() == null ? null : version.downloads().client())
                .orElse(versionDirectory.resolve(minecraftVersion + ".jar"));
    }

    private Optional<Path> libraryPath(CacheLayout cache, Path versionJsonPath, VersionJson.Library library) {
        VersionJson.LibraryDownloads downloads = library.downloads();
        if (downloads != null) {
            return artifactPath(cache.libraries(), downloads.artifact());
        }
        if (!text(library.name())) {
            throw new IllegalStateException("cache metadata missing library name or artifact path in " + versionJsonPath);
        }
        return Optional.of(cache.libraries().resolve(mavenPath(library.name(), versionJsonPath)));
    }

    private Optional<Path> nativeLibraryPath(CacheLayout cache, VersionJson.Library library) {
        return artifactPath(cache.libraries(), nativeArtifact(library));
    }

    private Optional<Path> artifactPath(Path root, VersionJson.Artifact artifact) {
        if (artifact == null || !text(artifact.path())) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(artifact.path()));
    }

    private String mavenPath(String coordinate, Path versionJsonPath) {
        String[] coordinateAndExtension = coordinate.split("@", 2);
        String extension = coordinateAndExtension.length == 2 ? coordinateAndExtension[1] : "jar";
        String[] parts = coordinateAndExtension[0].split(":");
        if (parts.length < 3) {
            throw new IllegalStateException("cache metadata has invalid library coordinate " + coordinate
                    + " in " + versionJsonPath);
        }

        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "."
                + extension;
    }

    private void ensureAssets(CacheLayout cache, VersionJson version, LaunchContext context) {
        VersionJson.AssetIndex assetIndex = version.assetIndex();
        if (assetIndex == null || !text(assetIndex.url()) || !text(assetIndex.id())) {
            return;
        }
        Path indexPath = cache.assets().resolve("indexes").resolve(assetIndex.id() + ".json");
        ensureDownload(indexPath, "Minecraft asset index", URI.create(assetIndex.url()), "SHA-1", assetIndex.sha1(), context);
        URI resolvedAssetBaseUri = ProductionLaunchSupport.uriHint(context.spec(), "minecraftAssetBaseUri", assetBaseUri);
        assetDownloader.downloadAssets(indexPath, cache, resolvedAssetBaseUri, context);
    }

    private static String nativeClassifier(VersionJson.Library library) {
        if (library.natives().isEmpty()) {
            return null;
        }
        String os = currentMinecraftOsName();
        String classifier = library.natives().get(os);
        if (!text(classifier)) {
            return null;
        }
        return classifier.replace("${arch}", currentMinecraftArchBits());
    }

    private static String currentMinecraftOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    private static String currentMinecraftArchBits() {
        String arch = System.getProperty("os.arch", "");
        return arch.contains("64") || arch.equalsIgnoreCase("aarch64") || arch.equalsIgnoreCase("arm64") ? "64" : "32";
    }

    private void extractNativeLibrary(Path nativeJar, Path nativesDir) {
        try {
            Files.createDirectories(nativesDir);
            try (InputStream input = Files.newInputStream(nativeJar);
                    ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || excludedNativeEntry(entry.getName())) {
                        continue;
                    }
                    Path target = nativesDir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(nativesDir)) {
                        throw new IOException("native entry escapes natives directory: " + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to extract minecraft native library at " + nativeJar, exception);
        }
    }

    private static boolean excludedNativeEntry(String name) {
        return name.startsWith("META-INF/")
                || name.endsWith(".git")
                || name.endsWith(".sha1");
    }

    private List<String> metadataJvmArgs(VersionJson version) {
        if (version.arguments() == null) {
            return List.of();
        }
        return argumentValues(version.arguments().jvm());
    }

    private List<String> metadataGameArgs(VersionJson version) {
        if (version.arguments() != null && !version.arguments().game().isEmpty()) {
            return argumentValues(version.arguments().game());
        }
        if (text(version.minecraftArguments())) {
            return List.of(version.minecraftArguments().split("\\s+"));
        }
        return List.of();
    }

    private List<String> argumentValues(List<Object> rawArguments) {
        List<String> values = new ArrayList<>();
        for (Object rawArgument : rawArguments) {
            addArgumentValue(values, rawArgument);
        }
        return values;
    }

    private void addArgumentValue(List<String> values, Object rawArgument) {
        if (rawArgument instanceof String value) {
            values.add(value);
            return;
        }
        if (!(rawArgument instanceof Map<?, ?> map)) {
            return;
        }
        Object rawRules = map.get("rules");
        if (rawRules instanceof List<?> rules && !ruleEvaluator.allowed(ruleMaps(rules))) {
            return;
        }
        Object rawValue = map.get("value");
        if (rawValue instanceof String value) {
            values.add(value);
        } else if (rawValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String value) {
                    values.add(value);
                }
            }
        }
    }

    private List<Map<String, Object>> ruleMaps(List<?> rules) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object rule : rules) {
            if (rule instanceof Map<?, ?> map) {
                maps.add(stringObjectMap(map));
            }
        }
        return maps;
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                values.put(key, entry.getValue());
            }
        }
        return values;
    }

    private List<String> interpolate(List<String> arguments, Map<String, String> placeholders) {
        return arguments.stream()
                .map(argument -> interpolate(argument, placeholders))
                .toList();
    }

    private String interpolate(String argument, Map<String, String> placeholders) {
        String interpolated = argument;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            interpolated = interpolated.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return interpolated;
    }

    private Map<String, String> placeholders(
            LaunchContext context,
            CacheLayout cache,
            VersionJson version,
            List<Path> classpath) {
        Map<String, String> placeholders = placeholders(context.spec(), cache, version, classpath);
        applyAuthResult(placeholders, context.authResult());
        return placeholders;
    }

    private Map<String, String> placeholders(
            LaunchSpec spec,
            CacheLayout cache,
            VersionJson version,
            List<Path> classpath) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("auth_player_name", authPlayerName(spec));
        placeholders.put("version_name", spec.minecraftVersion());
        placeholders.put("game_directory", path(spec.paths().workingDir()));
        placeholders.put("assets_root", path(spec.paths().assetsDir()));
        placeholders.put("assets_index_name", assetIndexName(spec, version));
        placeholders.put("auth_uuid", offlineUuid(authPlayerName(spec)));
        placeholders.put("auth_access_token", authAccessToken(spec));
        placeholders.put("clientid", "");
        placeholders.put("auth_xuid", "");
        placeholders.put("user_type", authUserType(spec));
        placeholders.put("version_type", version != null && text(version.type()) ? version.type() : "release");
        placeholders.put("natives_directory", path(spec.paths().nativesDir()));
        placeholders.put("launcher_name", "production-gradle");
        placeholders.put("launcher_version", "0.1.0-SNAPSHOT");
        placeholders.put("classpath", classpath(classpath));
        placeholders.put("classpath_separator", File.pathSeparator);
        placeholders.put("library_directory", path(spec.paths().librariesDir(), cache.libraries()));
        placeholders.put("game_assets", path(spec.paths().assetsDir()));
        placeholders.put("launcher_cache", path(spec.paths().cacheDir()));
        return placeholders;
    }

    private static void applyAuthResult(Map<String, String> placeholders, AuthProvider.AuthResult authResult) {
        if (authResult == null || authResult.status() != AuthProvider.AuthStatus.AUTHENTICATED) {
            return;
        }
        List<String> args = authResult.gameArguments();
        for (int index = 0; index < args.size() - 1; index++) {
            String key = args.get(index);
            String value = args.get(index + 1);
            switch (key) {
                case "--username" -> placeholders.put("auth_player_name", value);
                case "--uuid" -> placeholders.put("auth_uuid", value.replace("-", ""));
                case "--accessToken" -> placeholders.put("auth_access_token", value);
                case "--userType" -> placeholders.put("user_type", value);
                default -> {
                }
            }
        }
    }

    private String classpath(List<Path> classpath) {
        return classpath.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String assetIndexName(LaunchSpec spec, VersionJson version) {
        if (version != null && version.assetIndex() != null && text(version.assetIndex().id())) {
            return version.assetIndex().id();
        }
        return version != null && text(version.id()) ? version.id() : spec.minecraftVersion();
    }

    private static String authPlayerName(LaunchSpec spec) {
        if (spec.auth() != null && text(spec.auth().userName())) {
            return spec.auth().userName();
        }
        return "Player";
    }

    private static String offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private static String authAccessToken(LaunchSpec spec) {
        if (spec.auth() != null && "offline".equals(spec.auth().mode())) {
            return "offline";
        }
        return "";
    }

    private static String authUserType(LaunchSpec spec) {
        if (spec.auth() != null && text(spec.auth().mode())) {
            return spec.auth().mode();
        }
        return "offline";
    }

    private static String path(Path path) {
        return path == null ? "" : path.toString();
    }

    private static String path(Path preferred, Path fallback) {
        return preferred == null ? path(fallback) : preferred.toString();
    }

    private static String clientMainClass(LaunchSpec spec, VersionJson version, Path versionJsonPath) {
        if (text(spec.launch().mainClass())) {
            return spec.launch().mainClass();
        }
        if (text(version.mainClass())) {
            return version.mainClass();
        }
        throw new IllegalStateException("cache metadata missing mainClass in " + versionJsonPath);
    }

    private static String serverMainClass(LaunchSpec spec, Path serverJar) throws Exception {
        if (text(spec.launch().mainClass())) {
            return spec.launch().mainClass();
        }
        String manifestMainClass = manifestMainClass(serverJar);
        if (text(manifestMainClass)) {
            return manifestMainClass;
        }
        return "net.minecraft.bundler.Main";
    }

    private static String manifestMainClass(Path serverJar) throws Exception {
        try (JarFile jarFile = new JarFile(serverJar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attributes = manifest.getMainAttributes();
            return attributes == null ? null : attributes.getValue(Attributes.Name.MAIN_CLASS);
        }
    }

    private static void requireCachedFile(Path path, String description) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("cache miss: missing " + description.toLowerCase(Locale.ROOT)
                    + " at " + path);
        }
    }

    private static boolean offline(LaunchSpec spec) {
        if (spec.gradle() == null) {
            return false;
        }
        Object value = spec.gradle().get("offline");
        if (value instanceof Boolean offline) {
            return offline;
        }
        if (value instanceof String offline) {
            return Boolean.parseBoolean(offline);
        }
        return false;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VersionManifest(List<Version> versions) {
        private Optional<Version> find(String id) {
            if (versions == null) {
                return Optional.empty();
            }
            return versions.stream()
                    .filter(version -> id.equals(version.id()))
                    .findFirst();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Version(String id, URI url, String sha1) {
        }
    }

}
