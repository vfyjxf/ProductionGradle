package dev.vfyjxf.gradle.mods;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CurseForgeProvider implements ModProvider {
    private static final URI DEFAULT_BASE_URI = URI.create("https://api.curseforge.com");
    private static final int HASH_SHA1 = 1;
    private static final int RELATION_OPTIONAL = 2;
    private static final int RELATION_REQUIRED = 3;
    private static final int RELATION_INCOMPATIBLE = 5;
    private static final int MOD_LOADER_FORGE = 1;
    private static final int MOD_LOADER_FABRIC = 4;
    private static final int MOD_LOADER_QUILT = 5;
    private static final int MOD_LOADER_NEOFORGE = 6;
    private static final int DEPENDENCY_PAGE_SIZE = 50;

    private final HttpUrl baseUrl;
    private final OkHttpClient client;
    private final Downloader downloader;
    private final ObjectMapper mapper;

    public CurseForgeProvider() {
        this(DEFAULT_BASE_URI);
    }

    public CurseForgeProvider(URI baseUri) {
        this(baseUri, new OkHttpClient());
    }

    public CurseForgeProvider(URI baseUri, OkHttpClient client) {
        this(baseUri, client, new Downloader(client));
    }

    public CurseForgeProvider(URI baseUri, OkHttpClient client, Downloader downloader) {
        this.baseUrl = HttpUrl.get(Objects.requireNonNull(baseUri, "baseUri").toString());
        this.client = Objects.requireNonNull(client, "client");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.mapper = Json.mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public boolean supports(ModRequest request) {
        return request != null && request.type() == ModRequest.Type.CURSEFORGE_FILE;
    }

    @Override
    public ModProviderResult resolve(ModProviderContext context, ModRequest request) throws ModResolutionException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        if (!supports(request)) {
            throw new ModResolutionException("Unsupported mod request for CurseForge provider: " + request.type());
        }
        String apiKey = context.curseForgeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModResolutionException(
                    "CurseForge API key is required. Set production.curseforgeApiKey or CURSEFORGE_API_KEY.");
        }

        List<ModProviderResult.ModFile> mods = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();
        resolveFile(context, apiKey, request.projectId(), request.fileId(), mods, seenFiles);
        return ModProviderResult.of(mods);
    }

    private void resolveFile(
            ModProviderContext context,
            String apiKey,
            int projectId,
            int fileId,
            List<ModProviderResult.ModFile> mods,
            Set<String> seenFiles) throws ModResolutionException {
        CurseForgeFile file = fetchFile(context, apiKey, projectId, fileId);
        resolveFetchedFile(context, apiKey, projectId, file, mods, seenFiles);
    }

    private void resolveFetchedFile(
            ModProviderContext context,
            String apiKey,
            int projectId,
            CurseForgeFile file,
            List<ModProviderResult.ModFile> mods,
            Set<String> seenFiles) throws ModResolutionException {
        requireCompatible(context, file);
        String key = projectId + ":" + file.id();
        if (!seenFiles.add(key)) {
            return;
        }

        Path target = context.cacheLayout()
                .curseforge()
                .resolve(Integer.toString(projectId))
                .resolve(Integer.toString(file.id()))
                .resolve(safeFilename(file.fileName(), file.id() + ".jar"));
        download(context, file, target, "CurseForge " + projectId + " " + file.id());
        mods.add(new ModProviderResult.ModFile(
                "curseforge",
                Integer.toString(projectId),
                Integer.toString(file.id()),
                target,
                Map.of(
                        "projectId", Integer.toString(projectId),
                        "fileId", Integer.toString(file.id()),
                        "filename", target.getFileName().toString())));

        for (CurseForgeDependency dependency : file.dependencies()) {
            int relationType = dependency.relationType();
            if (relationType == RELATION_INCOMPATIBLE) {
                throw new ModResolutionException("CurseForge dependency relation for mod "
                        + dependency.modId() + " is incompatible with this run");
            }
            if (relationType == RELATION_REQUIRED && context.includeRequiredDependencies()) {
                resolveDependency(context, apiKey, dependency, mods, seenFiles);
            } else if (relationType == RELATION_OPTIONAL && context.includeOptionalDependencies()) {
                resolveDependency(context, apiKey, dependency, mods, seenFiles);
            }
        }
    }

    private void resolveDependency(
            ModProviderContext context,
            String apiKey,
            CurseForgeDependency dependency,
            List<ModProviderResult.ModFile> mods,
            Set<String> seenFiles) throws ModResolutionException {
        if (dependency.fileId() != null && dependency.fileId() > 0) {
            resolveFile(context, apiKey, dependency.modId(), dependency.fileId(), mods, seenFiles);
            return;
        }
        CurseForgeFile file = fetchCompatibleProjectFile(context, apiKey, dependency.modId());
        resolveFetchedFile(context, apiKey, dependency.modId(), file, mods, seenFiles);
    }

    private CurseForgeFile fetchFile(ModProviderContext context, String apiKey, int projectId, int fileId)
            throws ModResolutionException {
        HttpUrl url = baseUrl.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("mods")
                .addPathSegment(Integer.toString(projectId))
                .addPathSegment("files")
                .addPathSegment(Integer.toString(fileId))
                .build();
        requireOnlineMetadata(context, "CurseForge file metadata", url);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ModResolutionException("CurseForge API request failed for " + url + ": HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModResolutionException("CurseForge API request failed for " + url + ": empty response body");
            }
            CurseForgeEnvelope envelope = mapper.readValue(body.string(), CurseForgeEnvelope.class);
            if (envelope.data() == null) {
                throw new ModResolutionException("CurseForge API response for " + url + " did not contain file metadata");
            }
            return envelope.data();
        } catch (IOException exception) {
            throw new ModResolutionException("CurseForge API request failed for " + url + ": " + exception.getMessage(), exception);
        }
    }

    private CurseForgeFile fetchCompatibleProjectFile(
            ModProviderContext context,
            String apiKey,
            int projectId) throws ModResolutionException {
        HttpUrl.Builder url = baseUrl.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("mods")
                .addPathSegment(Integer.toString(projectId))
                .addPathSegment("files")
                .addQueryParameter("gameVersion", context.minecraftVersion());
        Integer modLoaderType = modLoaderType(context.loader());
        if (modLoaderType != null) {
            url.addQueryParameter("modLoaderType", Integer.toString(modLoaderType));
        }
        url.addQueryParameter("pageSize", Integer.toString(DEPENDENCY_PAGE_SIZE));

        List<CurseForgeFile> files = fetchFiles(context, apiKey, url.build());
        return files.stream()
                .filter(file -> compatible(context, file))
                .findFirst()
                .orElseThrow(() -> new ModResolutionException(
                        "No CurseForge file for mod " + projectId
                                + " matches Minecraft " + context.minecraftVersion()
                                + " and loader " + context.loader()));
    }

    private List<CurseForgeFile> fetchFiles(ModProviderContext context, String apiKey, HttpUrl url)
            throws ModResolutionException {
        requireOnlineMetadata(context, "CurseForge project files metadata", url);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ModResolutionException("CurseForge API request failed for " + url + ": HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModResolutionException("CurseForge API request failed for " + url + ": empty response body");
            }
            CurseForgeFilesEnvelope envelope = mapper.readValue(body.string(), CurseForgeFilesEnvelope.class);
            return envelope.data();
        } catch (IOException exception) {
            throw new ModResolutionException("CurseForge API request failed for " + url + ": " + exception.getMessage(), exception);
        }
    }

    private static void requireOnlineMetadata(
            ModProviderContext context,
            String description,
            HttpUrl url) throws ModResolutionException {
        if (context.offline()) {
            throw new ModResolutionException(description + " is unavailable in Gradle offline mode: " + url);
        }
    }

    private void requireCompatible(ModProviderContext context, CurseForgeFile file) throws ModResolutionException {
        if (!compatible(context, file)) {
            throw new ModResolutionException(
                    "CurseForge file " + file.id()
                            + " is incompatible with Minecraft " + context.minecraftVersion()
                            + " and loader " + context.loader());
        }
        if (file.downloadUrl() == null || file.downloadUrl().isBlank()) {
            throw new ModResolutionException("CurseForge file " + file.id() + " does not expose a downloadUrl");
        }
    }

    private boolean compatible(ModProviderContext context, CurseForgeFile file) {
        return containsExact(file.gameVersions(), context.minecraftVersion())
                && containsIgnoreCase(file.gameVersions(), context.loader());
    }

    private void download(ModProviderContext context, CurseForgeFile file, Path target, String description)
            throws ModResolutionException {
        String sha1 = file.hashes().stream()
                .filter(hash -> hash.algo() == HASH_SHA1)
                .map(CurseForgeHash::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        try {
            downloader.download(new DownloadRequest(
                    URI.create(file.downloadUrl()),
                    target,
                    sha1 == null ? null : "sha1",
                    sha1,
                    context.offline()));
        } catch (IllegalArgumentException | IOException exception) {
            throw new ModResolutionException("Failed to download " + description + ": " + exception.getMessage(), exception);
        }
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

    private static Integer modLoaderType(String loader) {
        return switch (loader.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> MOD_LOADER_FORGE;
            case "fabric" -> MOD_LOADER_FABRIC;
            case "quilt" -> MOD_LOADER_QUILT;
            case "neoforge", "neo_forge", "neo-forge" -> MOD_LOADER_NEOFORGE;
            default -> null;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurseForgeEnvelope(CurseForgeFile data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurseForgeFilesEnvelope(List<CurseForgeFile> data) {
        private CurseForgeFilesEnvelope {
            data = data == null ? List.of() : List.copyOf(data);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurseForgeFile(
            int id,
            int modId,
            String fileName,
            String displayName,
            String downloadUrl,
            List<String> gameVersions,
            List<CurseForgeDependency> dependencies,
            List<CurseForgeHash> hashes) {
        private CurseForgeFile {
            gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            hashes = hashes == null ? List.of() : List.copyOf(hashes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurseForgeDependency(
            @JsonProperty("modId") int modId,
            @JsonProperty("relationType") int relationType,
            Integer fileId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurseForgeHash(String value, int algo) {
    }
}
