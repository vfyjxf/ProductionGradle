package dev.vfyjxf.gradle.launcher.core.loader.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vfyjxf.gradle.launcher.core.auth.AuthProvider;
import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.download.DownloadRequest;
import dev.vfyjxf.gradle.launcher.core.download.Downloader;
import dev.vfyjxf.gradle.launcher.core.download.OfflineCacheMissException;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidator;
import dev.vfyjxf.gradle.launcher.core.minecraft.LibraryRuleEvaluator;
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
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;

public final class ProductionLaunchSupport {
    private static final URI DEFAULT_VERSION_MANIFEST_URI = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final URI DEFAULT_ASSET_BASE_URI = URI.create("https://resources.download.minecraft.net/");
    private final LaunchSpecValidator validator;
    private final LibraryRuleEvaluator ruleEvaluator;
    private final Downloader downloader;
    private final MinecraftAssetDownloader assetDownloader;

    public ProductionLaunchSupport() {
        this(new LaunchSpecValidator(), LibraryRuleEvaluator.current());
    }

    public ProductionLaunchSupport(LaunchSpecValidator validator, LibraryRuleEvaluator ruleEvaluator) {
        this(validator, ruleEvaluator, new Downloader());
    }

    public ProductionLaunchSupport(
            LaunchSpecValidator validator,
            LibraryRuleEvaluator ruleEvaluator,
            Downloader downloader) {
        this.validator = validator;
        this.ruleEvaluator = ruleEvaluator;
        this.downloader = downloader;
        this.assetDownloader = new MinecraftAssetDownloader(downloader);
    }

    public void validate(LaunchSpec spec, String loader) {
        validator.validate(spec);
        if (!loader.equals(spec.loader())) {
            throw new IllegalArgumentException("unsupported loader for " + loader + " resolver: " + spec.loader());
        }
    }

    public CacheLayout cacheLayout(LaunchSpec spec) {
        Path cacheDir = spec.paths().cacheDir();
        if (cacheDir == null) {
            throw new IllegalArgumentException("paths.cacheDir is required");
        }
        return CacheLayout.under(cacheDir);
    }

    public VersionJson readMinecraftVersion(LaunchSpec spec, CacheLayout cache) throws IOException {
        Path versionJsonPath = versionJsonPath(cache, spec.minecraftVersion());
        requireCachedFile(versionJsonPath, "Minecraft version metadata");
        return VersionJson.read(versionJsonPath);
    }

    public VersionJson resolveMinecraftVersion(LaunchContext context, CacheLayout cache) throws IOException {
        LaunchSpec spec = context.spec();
        Path versionJsonPath = versionJsonPath(cache, spec.minecraftVersion());
        ensureMinecraftVersionMetadata(versionJsonPath, context);
        return VersionJson.read(versionJsonPath);
    }

    public Path minecraftJar(LaunchSpec spec, CacheLayout cache, VersionJson version) {
        Path versionDirectory = versionDirectory(cache, spec.minecraftVersion());
        if ("server".equals(spec.type())) {
            return artifactPath(cache.minecraft(), version.downloads() == null ? null : version.downloads().server())
                    .orElse(versionDirectory.resolve(spec.minecraftVersion() + "-server.jar"));
        }
        return artifactPath(cache.minecraft(), version.downloads() == null ? null : version.downloads().client())
                .orElse(versionDirectory.resolve(spec.minecraftVersion() + ".jar"));
    }

    public List<Path> minecraftLibraries(CacheLayout cache, VersionJson version) {
        return libraries(cache, version, null, "Minecraft library");
    }

    public List<Path> resolveMinecraftLibraries(CacheLayout cache, VersionJson version, LaunchContext context) {
        return resolveLibraries(cache, version, null, "Minecraft library", null, context);
    }

    public List<Path> resolveMinecraftClasspath(CacheLayout cache, VersionJson version, LaunchContext context) {
        List<Path> classpath = new ArrayList<>();
        for (VersionJson.Library library : version.libraries()) {
            if (!ruleEvaluator.allowed(library.rules())) {
                continue;
            }
            libraryPath(cache, library, null).ifPresent(libraryPath -> {
                ensureLibrary(libraryPath, library, "Minecraft library", null, null, context);
                classpath.add(libraryPath);
            });
            nativeLibraryPath(cache, library).ifPresent(nativePath -> {
                ensureNativeLibrary(nativePath, library, context);
                extractNativeLibrary(nativePath, context.spec().paths().nativesDir());
                if (!classpath.contains(nativePath)) {
                    classpath.add(nativePath);
                }
            });
        }
        return classpath;
    }

    public void resolveAssets(CacheLayout cache, VersionJson version, LaunchContext context) {
        VersionJson.AssetIndex assetIndex = version.assetIndex();
        if (assetIndex == null || !text(assetIndex.url()) || !text(assetIndex.id())) {
            return;
        }
        Path indexPath = cache.assets().resolve("indexes").resolve(assetIndex.id() + ".json");
        ensureDownload(indexPath, "Minecraft asset index", URI.create(assetIndex.url()), "SHA-1", assetIndex.sha1(), context);
        URI assetBaseUri = uriHint(context.spec(), "minecraftAssetBaseUri", DEFAULT_ASSET_BASE_URI);
        assetDownloader.downloadAssets(indexPath, cache, assetBaseUri, context);
    }

    public List<Path> libraries(
            CacheLayout cache,
            VersionJson version,
            Path metadataPath,
            String description) {
        List<Path> classpath = new ArrayList<>();
        for (VersionJson.Library library : version.libraries()) {
            if (!ruleEvaluator.allowed(library.rules())) {
                continue;
            }
            libraryPath(cache, library, metadataPath).ifPresent(libraryPath -> {
                requireCachedFile(libraryPath, description);
                classpath.add(libraryPath);
            });
        }
        return classpath;
    }

    public List<Path> resolveLibraries(
            CacheLayout cache,
            VersionJson version,
            Path metadataPath,
            String description,
            URI fallbackMavenBaseUri,
            LaunchContext context) {
        return resolveLibraries(
                cache,
                version.libraries(),
                metadataPath,
                description,
                fallbackMavenBaseUri,
                context);
    }

    public List<Path> resolveLibraries(
            CacheLayout cache,
            List<VersionJson.Library> libraries,
            Path metadataPath,
            String description,
            URI fallbackMavenBaseUri,
            LaunchContext context) {
        List<Path> classpath = new ArrayList<>();
        for (VersionJson.Library library : libraries) {
            if (!ruleEvaluator.allowed(library.rules())) {
                continue;
            }
            libraryPath(cache, library, metadataPath).ifPresent(libraryPath -> {
                ensureLibrary(libraryPath, library, description, metadataPath, fallbackMavenBaseUri, context);
                classpath.add(libraryPath);
            });
        }
        return classpath;
    }

    public Path loaderMetadata(CacheLayout cache, String loader, String minecraftVersion, String loaderVersion, String fileName) {
        return cache.loaders()
                .resolve(loader)
                .resolve(minecraftVersion)
                .resolve(loaderVersion)
                .resolve(fileName);
    }

    public void resolveDownload(Path target, String description, URI uri, LaunchContext context) {
        ensureDownload(target, description, uri, null, null, context);
    }

    public void resolveDownload(
            Path target,
            String description,
            URI uri,
            String checksumAlgorithm,
            String checksum,
            LaunchContext context) {
        ensureDownload(target, description, uri, checksumAlgorithm, checksum, context);
    }

    public void resolveMavenArtifact(
            CacheLayout cache,
            String coordinate,
            Path metadataPath,
            String description,
            URI mavenBaseUri,
            LaunchContext context) {
        Path artifactPath = cache.libraries().resolve(mavenArtifactPath(coordinate, metadataPath));
        URI artifactUri = mavenBaseUri.resolve(mavenArtifactPath(coordinate, metadataPath));
        ensureDownload(artifactPath, description, artifactUri, null, null, context);
    }

    public String mavenArtifactPath(String coordinate, Path metadataPath) {
        return mavenPath(coordinate, metadataPath);
    }

    public void extractInstallerVersionJson(Path installerJar, Path versionJsonPath, String description) throws IOException {
        if (Files.isRegularFile(versionJsonPath)) {
            return;
        }
        Files.createDirectories(versionJsonPath.getParent());
        try (InputStream input = Files.newInputStream(installerJar);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "version.json".equals(entry.getName())) {
                    Files.copy(zip, versionJsonPath, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IllegalStateException(description + " is missing version.json: " + installerJar);
    }

    public List<String> minecraftJvmArgs(VersionJson version, Map<String, String> placeholders) {
        return interpolate(argumentValues(version.arguments().jvm()), placeholders);
    }

    public List<String> minecraftGameArgs(VersionJson version, Map<String, String> placeholders) {
        if (!version.arguments().game().isEmpty()) {
            return interpolate(argumentValues(version.arguments().game()), placeholders);
        }
        if (text(version.minecraftArguments())) {
            return interpolate(List.of(version.minecraftArguments().split("\\s+")), placeholders);
        }
        return List.of();
    }

    public List<String> metadataArgs(List<Object> rawArguments, Map<String, String> placeholders) {
        return interpolate(argumentValues(rawArguments), placeholders);
    }

    public List<String> interpolate(List<String> arguments, Map<String, String> placeholders) {
        return arguments.stream()
                .map(argument -> interpolate(argument, placeholders))
                .toList();
    }

    public Map<String, String> placeholders(
            LaunchContext context,
            CacheLayout cache,
            VersionJson version,
            List<Path> classpath) {
        Map<String, String> placeholders = placeholders(context.spec(), cache, version, classpath);
        applyAuthResult(placeholders, context.authResult());
        return placeholders;
    }

    public Map<String, String> placeholders(
            LaunchSpec spec,
            CacheLayout cache,
            VersionJson version,
            List<Path> classpath) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("auth_player_name", authPlayerName(spec));
        placeholders.put("version_name", spec.minecraftVersion());
        placeholders.put("minecraft_version", spec.minecraftVersion());
        placeholders.put("loader_version", spec.loaderVersion());
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

    public Path libraryPath(CacheLayout cache, LoaderLibrary library, Path metadataPath) {
        if (text(library.path())) {
            return cache.libraries().resolve(library.path());
        }
        if (!text(library.name())) {
            throw new IllegalStateException("cache metadata missing library name or artifact path in " + metadataPath);
        }
        return cache.libraries().resolve(mavenPath(library.name(), metadataPath));
    }

    public static void requireCachedFile(Path path, String description) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("cache miss: missing " + description.toLowerCase(Locale.ROOT)
                    + " at " + path);
        }
    }

    public void requireCachedFile(Path path, String description, LaunchContext context, URI sourceUri) {
        if (Files.isRegularFile(path)) {
            return;
        }
        if (context.offline()) {
            throw new IllegalStateException(new OfflineCacheMissException(sourceUri, path).getMessage());
        }
        requireCachedFile(path, description);
    }

    private Optional<Path> libraryPath(CacheLayout cache, VersionJson.Library library, Path metadataPath) {
        VersionJson.LibraryDownloads downloads = library.downloads();
        if (downloads != null) {
            return artifactPath(cache.libraries(), downloads.artifact());
        }
        if (!text(library.name())) {
            String location = metadataPath == null ? "" : " in " + metadataPath;
            throw new IllegalStateException("cache metadata missing library name or artifact path" + location);
        }
        return Optional.of(cache.libraries().resolve(mavenPath(library.name(), metadataPath)));
    }

    private Optional<Path> nativeLibraryPath(CacheLayout cache, VersionJson.Library library) {
        return artifactPath(cache.libraries(), nativeArtifact(library));
    }

    private void ensureMinecraftVersionMetadata(Path versionJsonPath, LaunchContext context) throws IOException {
        if (Files.isRegularFile(versionJsonPath)) {
            return;
        }
        URI manifestUri = uriHint(context.spec(), "minecraftVersionManifestUri", DEFAULT_VERSION_MANIFEST_URI);
        if (context.offline()) {
            throw new IllegalStateException(new OfflineCacheMissException(manifestUri, versionJsonPath).getMessage());
        }
        VersionManifest manifest = Json.mapper().readValue(manifestUri.toURL(), VersionManifest.class);
        VersionManifest.Version version = manifest.find(context.spec().minecraftVersion())
                .orElseThrow(() -> new IllegalStateException("Minecraft version " + context.spec().minecraftVersion()
                        + " not found in " + manifestUri));
        ensureDownload(versionJsonPath, "Minecraft version metadata", version.url(), "SHA-1", version.sha1(), context);
    }

    public void ensureMinecraftJar(
            Path minecraftJar,
            String description,
            VersionJson version,
            String type,
            LaunchContext context) {
        VersionJson.Artifact artifact = minecraftArtifact(version, type);
        ensureArtifact(minecraftJar, description, artifact, context);
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
            requireCachedFile(path, description, context, missingUri(description));
            return;
        }
        ensureDownload(path, description, URI.create(artifact.url()), "SHA-1", artifact.sha1(), context);
    }

    private void ensureLibrary(
            Path path,
            VersionJson.Library library,
            String description,
            Path metadataPath,
            URI fallbackMavenBaseUri,
            LaunchContext context) {
        if (Files.isRegularFile(path)) {
            return;
        }
        VersionJson.Artifact artifact = libraryArtifact(library);
        if (artifact != null && text(artifact.url())) {
            ensureDownload(path, description, URI.create(artifact.url()), "SHA-1", artifact.sha1(), context);
            return;
        }
        if (fallbackMavenBaseUri == null) {
            requireCachedFile(path, description, context, missingUri(description));
            return;
        }
        if (!text(library.name())) {
            String location = metadataPath == null ? "" : " in " + metadataPath;
            throw new IllegalStateException("cache metadata missing library name or artifact path" + location);
        }
        URI uri = fallbackMavenBaseUri.resolve(mavenPath(library.name(), metadataPath));
        ensureDownload(path, description, uri, null, null, context);
    }

    private void ensureNativeLibrary(Path path, VersionJson.Library library, LaunchContext context) {
        VersionJson.Artifact artifact = nativeArtifact(library);
        ensureArtifact(path, "Minecraft native library", artifact, context);
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

    private static Path versionDirectory(CacheLayout cache, String minecraftVersion) {
        return cache.minecraft().resolve("versions").resolve(minecraftVersion);
    }

    private static Path versionJsonPath(CacheLayout cache, String minecraftVersion) {
        return versionDirectory(cache, minecraftVersion).resolve(minecraftVersion + ".json");
    }

    private static Optional<Path> artifactPath(Path root, VersionJson.Artifact artifact) {
        if (artifact == null || !text(artifact.path())) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(artifact.path()));
    }

    private List<String> argumentValues(List<Object> rawArguments) {
        if (rawArguments == null || rawArguments.isEmpty()) {
            return List.of();
        }
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

    private static List<Map<String, Object>> ruleMaps(List<?> rules) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object rule : rules) {
            if (rule instanceof Map<?, ?> map) {
                maps.add(stringObjectMap(map));
            }
        }
        return maps;
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                values.put(key, entry.getValue());
            }
        }
        return values;
    }

    private static String mavenPath(String coordinate, Path metadataPath) {
        String[] coordinateAndExtension = coordinate.split("@", 2);
        String extension = coordinateAndExtension.length == 2 ? coordinateAndExtension[1] : "jar";
        String[] parts = coordinateAndExtension[0].split(":");
        if (parts.length < 3) {
            String location = metadataPath == null ? "" : " in " + metadataPath;
            throw new IllegalStateException("cache metadata has invalid library coordinate " + coordinate + location);
        }

        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "."
                + extension;
    }

    private static String classpath(List<Path> classpath) {
        return classpath.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String interpolate(String argument, Map<String, String> placeholders) {
        String interpolated = argument;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            interpolated = interpolated.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return interpolated;
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

    public static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    public static <T> T read(Path path, Class<T> type) throws IOException {
        requireCachedFile(path, "loader metadata");
        return Json.mapper().readValue(path.toFile(), type);
    }

    public static <T> T read(Path path, Class<T> type, String description) throws IOException {
        requireCachedFile(path, description);
        return Json.mapper().readValue(path.toFile(), type);
    }

    public static URI uriHint(LaunchSpec spec, String name, URI defaultValue) {
        if (spec != null && spec.resolutionHints() != null) {
            Object value = spec.resolutionHints().get(name);
            if (value instanceof String text && text(text)) {
                return URI.create(text);
            }
        }
        return defaultValue;
    }

    private static URI missingUri(String description) {
        return URI.create("production-gradle-cache-miss:" + description.toLowerCase(Locale.ROOT).replace(' ', '-'));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoaderLibrary(String name, String path, String url) {
        public LoaderLibrary(String name, String path) {
            this(name, path, null);
        }
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
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (ZipException exception) {
            throw new IllegalStateException("failed to extract minecraft native library at " + nativeJar, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to extract minecraft native library at " + nativeJar, exception);
        }
    }

    private static boolean excludedNativeEntry(String name) {
        return name.startsWith("META-INF/")
                || name.endsWith(".git")
                || name.endsWith(".sha1");
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
}
