package dev.vfyjxf.gradle.mods;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vfyjxf.gradle.launcher.core.download.DownloadRequest;
import dev.vfyjxf.gradle.launcher.core.download.Downloader;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModrinthProvider implements ModProvider {
    private static final URI DEFAULT_BASE_URI = URI.create("https://api.modrinth.com/v2");
    private static final TypeReference<List<ModrinthVersion>> VERSION_LIST = new TypeReference<>() {
    };

    private final HttpUrl baseUrl;
    private final OkHttpClient client;
    private final Downloader downloader;
    private final ObjectMapper mapper;

    public ModrinthProvider() {
        this(DEFAULT_BASE_URI);
    }

    public ModrinthProvider(URI baseUri) {
        this(baseUri, new OkHttpClient());
    }

    public ModrinthProvider(URI baseUri, OkHttpClient client) {
        this(baseUri, client, new Downloader(client));
    }

    public ModrinthProvider(URI baseUri, OkHttpClient client, Downloader downloader) {
        this.baseUrl = HttpUrl.get(Objects.requireNonNull(baseUri, "baseUri").toString());
        this.client = Objects.requireNonNull(client, "client");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.mapper = Json.mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public boolean supports(ModRequest request) {
        return request != null
                && (request.type() == ModRequest.Type.MODRINTH_PROJECT || request.type() == ModRequest.Type.MODRINTH_VERSION);
    }

    @Override
    public ModProviderResult resolve(ModProviderContext context, ModRequest request) throws ModResolutionException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        if (!supports(request)) {
            throw new ModResolutionException("Unsupported mod request for Modrinth provider: " + request.type());
        }

        List<ModProviderResult.ModFile> mods = new ArrayList<>();
        Set<String> seenVersions = new HashSet<>();
        ModrinthVersion version = switch (request.type()) {
            case MODRINTH_PROJECT -> selectProjectVersion(context, request.identifier(), request.version());
            case MODRINTH_VERSION -> fetchVersion(context, request.identifier());
            default -> throw new ModResolutionException("Unsupported mod request for Modrinth provider: " + request.type());
        };
        resolveVersion(context, version, mods, seenVersions);
        return ModProviderResult.of(mods);
    }

    private void resolveVersion(
            ModProviderContext context,
            ModrinthVersion version,
            List<ModProviderResult.ModFile> mods,
            Set<String> seenVersions) throws ModResolutionException {
        requireCompatible(context, version);
        if (!seenVersions.add(version.id())) {
            return;
        }

        ModrinthFile file = selectFile(version);
        Path target = context.cacheLayout()
                .modrinth()
                .resolve(version.projectId())
                .resolve(version.id())
                .resolve(safeFilename(file.filename(), version.id() + ".jar"));
        download(context, file, target, "Modrinth " + version.projectId() + " " + version.id());
        mods.add(new ModProviderResult.ModFile(
                "modrinth",
                version.projectId(),
                version.versionNumber(),
                target,
                Map.of(
                        "projectId", version.projectId(),
                        "versionId", version.id(),
                        "filename", target.getFileName().toString())));

        if (!context.includeRequiredDependencies() && !context.includeOptionalDependencies()) {
            return;
        }
        ModrinthDependencies dependencies = fetchDependencies(context, version.projectId());
        for (ModrinthDependency dependency : dependencies.dependencies()) {
            if (!shouldIncludeDependency(context, dependency)) {
                continue;
            }
            ModrinthVersion dependencyVersion = dependencyVersion(context, dependency, dependencies);
            resolveVersion(context, dependencyVersion, mods, seenVersions);
        }
    }

    private ModrinthVersion dependencyVersion(
            ModProviderContext context,
            ModrinthDependency dependency,
            ModrinthDependencies dependencies) throws ModResolutionException {
        Map<String, ModrinthVersion> versionsById = new LinkedHashMap<>();
        Map<String, List<ModrinthVersion>> versionsByProject = new LinkedHashMap<>();
        for (ModrinthVersion version : dependencies.versions()) {
            versionsById.put(version.id(), version);
            versionsByProject.computeIfAbsent(version.projectId(), ignored -> new ArrayList<>()).add(version);
        }

        if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            ModrinthVersion version = versionsById.get(dependency.versionId());
            return version == null ? fetchVersion(context, dependency.versionId()) : version;
        }
        if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
            List<ModrinthVersion> versions = versionsByProject.getOrDefault(dependency.projectId(), List.of());
            ModrinthVersion version = versions.stream()
                    .filter(candidate -> compatible(context, candidate))
                    .findFirst()
                    .orElse(null);
            if (version != null) {
                return version;
            }
            return selectProjectVersion(context, dependency.projectId(), null);
        }
        throw new ModResolutionException("Modrinth dependency did not include a project_id or version_id");
    }

    private boolean shouldIncludeDependency(ModProviderContext context, ModrinthDependency dependency) {
        String type = dependency.dependencyType() == null ? "" : dependency.dependencyType().trim().toLowerCase();
        return switch (type) {
            case "required" -> context.includeRequiredDependencies();
            case "optional" -> context.includeOptionalDependencies();
            default -> false;
        };
    }

    private ModrinthVersion selectProjectVersion(
            ModProviderContext context,
            String projectIdOrSlug,
            String versionNumber) throws ModResolutionException {
        HttpUrl url = url("project", projectIdOrSlug, "version");
        List<ModrinthVersion> versions = get(context, url, VERSION_LIST);
        return versions.stream()
                .filter(version -> versionNumber == null || versionNumber.equals(version.versionNumber()))
                .filter(version -> compatible(context, version))
                .findFirst()
                .orElseThrow(() -> new ModResolutionException(
                        "No Modrinth version for " + projectIdOrSlug
                                + " matches Minecraft " + context.minecraftVersion()
                                + " and loader " + context.loader()));
    }

    private ModrinthVersion fetchVersion(ModProviderContext context, String versionId) throws ModResolutionException {
        return get(context, url("version", versionId), ModrinthVersion.class);
    }

    private ModrinthDependencies fetchDependencies(ModProviderContext context, String projectId) throws ModResolutionException {
        return get(context, url("project", projectId, "dependencies"), ModrinthDependencies.class);
    }

    private void requireCompatible(ModProviderContext context, ModrinthVersion version) throws ModResolutionException {
        if (!compatible(context, version)) {
            throw new ModResolutionException(
                    "Modrinth version " + version.id()
                            + " is incompatible with Minecraft " + context.minecraftVersion()
                            + " and loader " + context.loader());
        }
    }

    private boolean compatible(ModProviderContext context, ModrinthVersion version) {
        return containsExact(version.gameVersions(), context.minecraftVersion())
                && containsIgnoreCase(version.loaders(), context.loader());
    }

    private ModrinthFile selectFile(ModrinthVersion version) throws ModResolutionException {
        if (version.files().isEmpty()) {
            throw new ModResolutionException("Modrinth version " + version.id() + " has no downloadable files");
        }
        return version.files().stream()
                .filter(file -> Boolean.TRUE.equals(file.primary()))
                .findFirst()
                .orElse(version.files().getFirst());
    }

    private void download(ModProviderContext context, ModrinthFile file, Path target, String description)
            throws ModResolutionException {
        String sha512 = file.hashes().get("sha512");
        String sha1 = file.hashes().get("sha1");
        String algorithm = sha512 == null || sha512.isBlank() ? "sha1" : "sha512";
        String checksum = sha512 == null || sha512.isBlank() ? sha1 : sha512;
        try {
            downloader.download(new DownloadRequest(
                    URI.create(file.url()),
                    target,
                    algorithm,
                    checksum,
                    context.offline()));
        } catch (IllegalArgumentException | IOException exception) {
            throw new ModResolutionException("Failed to download " + description + ": " + exception.getMessage(), exception);
        }
    }

    private <T> T get(ModProviderContext context, HttpUrl url, Class<T> type) throws ModResolutionException {
        return execute(context, url, body -> mapper.readValue(body, type));
    }

    private <T> T get(ModProviderContext context, HttpUrl url, TypeReference<T> type) throws ModResolutionException {
        return execute(context, url, body -> mapper.readValue(body, type));
    }

    private <T> T execute(ModProviderContext context, HttpUrl url, JsonReader<T> reader) throws ModResolutionException {
        if (context.offline()) {
            throw new ModResolutionException("Modrinth API metadata is unavailable in Gradle offline mode: " + url);
        }
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ModResolutionException("Modrinth API request failed for " + url + ": HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModResolutionException("Modrinth API request failed for " + url + ": empty response body");
            }
            return reader.read(body.string());
        } catch (IOException exception) {
            throw new ModResolutionException("Modrinth API request failed for " + url + ": " + exception.getMessage(), exception);
        }
    }

    private HttpUrl url(String first, String... rest) {
        HttpUrl.Builder builder = baseUrl.newBuilder().addPathSegment(first);
        for (String segment : rest) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private static boolean containsExact(List<String> values, String expected) {
        return values.stream().anyMatch(expected::equals);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private static String safeFilename(String filename, String fallback) {
        String value = filename == null || filename.isBlank() ? fallback : filename.trim();
        return Path.of(value.replace('\\', '/')).getFileName().toString();
    }

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String body) throws IOException, ModResolutionException;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthVersion(
            String id,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("version_number") String versionNumber,
            @JsonProperty("game_versions") List<String> gameVersions,
            List<String> loaders,
            List<ModrinthFile> files) {
        private ModrinthVersion {
            gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
            loaders = loaders == null ? List.of() : List.copyOf(loaders);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthFile(String filename, String url, Boolean primary, Map<String, String> hashes) {
        private ModrinthFile {
            hashes = hashes == null ? Map.of() : Map.copyOf(hashes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthDependencies(List<ModrinthVersion> versions, List<ModrinthDependency> dependencies) {
        private ModrinthDependencies {
            versions = versions == null ? List.of() : List.copyOf(versions);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModrinthDependency(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("version_id") String versionId,
            @JsonProperty("dependency_type") String dependencyType) {
    }
}
