package dev.vfyjxf.gradle.launcher.core.loader.fabric;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport.LoaderLibrary;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FabricResolver implements LoaderResolver {
    private static final URI DEFAULT_FABRIC_META_BASE_URI = URI.create(
            "https://meta.fabricmc.net/v2/versions/loader/");
    private static final URI DEFAULT_FABRIC_MAVEN_BASE_URI = URI.create("https://maven.fabricmc.net/");

    private final ProductionLaunchSupport support;

    public FabricResolver() {
        this(new ProductionLaunchSupport());
    }

    FabricResolver(ProductionLaunchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String loader) {
        return "fabric".equals(loader);
    }

    @Override
    public PreparedLaunch prepare(LaunchSpec spec) throws Exception {
        return prepare(LaunchContext.directCli(spec, false));
    }

    @Override
    public PreparedLaunch prepare(LaunchContext context) throws Exception {
        LaunchSpec spec = context.spec();
        support.validate(spec, "fabric");
        CacheLayout cache = support.cacheLayout(spec);
        VersionJson minecraftVersion = support.resolveMinecraftVersion(context, cache);
        FabricMetadataSource metadataSource = metadataSource(cache, spec, context);
        Path metadataPath = metadataSource.path();
        FabricMetadata metadata = metadataSource.metadata();

        if ("client".equals(spec.type())) {
            support.resolveAssets(cache, minecraftVersion, context);
        }
        List<Path> classpath = new ArrayList<>(support.resolveMinecraftClasspath(cache, minecraftVersion, context));
        Path minecraftJar = support.minecraftJar(spec, cache, minecraftVersion);
        support.ensureMinecraftJar(minecraftJar, "Minecraft " + spec.type() + " jar", minecraftVersion, spec.type(), context);
        classpath.add(minecraftJar);
        URI mavenBaseUri = ProductionLaunchSupport.uriHint(
                spec,
                "fabricMavenBaseUri",
                DEFAULT_FABRIC_MAVEN_BASE_URI);
        for (LoaderLibrary library : metadata.librariesFor(spec.type())) {
            Path libraryPath = support.libraryPath(cache, library, metadataPath);
            URI libraryMavenBaseUri = ProductionLaunchSupport.text(library.url())
                    ? URI.create(library.url())
                    : mavenBaseUri;
            if (ProductionLaunchSupport.text(library.name())) {
                support.resolveMavenArtifact(
                        cache,
                        library.name(),
                        metadataPath,
                        "Fabric loader library",
                        libraryMavenBaseUri,
                        context);
            } else {
                support.requireCachedFile(libraryPath, "Fabric loader library", context, libraryMavenBaseUri);
            }
            if (!classpath.contains(libraryPath)) {
                classpath.add(libraryPath);
            }
        }

        Map<String, String> placeholders = support.placeholders(context, cache, minecraftVersion, classpath);
        List<String> jvmArgs = new ArrayList<>(support.minecraftJvmArgs(minecraftVersion, placeholders));
        jvmArgs.addAll(support.metadataArgs(metadata.jvmArguments(), placeholders));
        jvmArgs.addAll(support.interpolate(spec.launch().jvmArgs(), placeholders));
        List<String> gameArgs = new ArrayList<>(support.minecraftGameArgs(minecraftVersion, placeholders));
        gameArgs.addAll(support.metadataArgs(metadata.gameArguments(), placeholders));
        gameArgs.addAll(support.interpolate(spec.launch().gameArgs(), placeholders));

        String mainClass = metadata.mainClass(spec.type());
        if (!ProductionLaunchSupport.text(mainClass)) {
            throw new IllegalStateException("cache metadata missing Fabric " + spec.type() + " main class in "
                    + metadataPath);
        }
        return new PreparedLaunch(classpath, jvmArgs, gameArgs, mainClass);
    }

    private FabricMetadataSource metadataSource(CacheLayout cache, LaunchSpec spec, LaunchContext context) throws Exception {
        Path metadataPath = metadataPath(cache, spec);
        if (Files.isRegularFile(metadataPath)) {
            return new FabricMetadataSource(metadataPath, ProductionLaunchSupport.read(metadataPath, FabricMetadata.class));
        }
        FabricMetadata metadata = legacyServerMetadata(cache, spec);
        if (metadata != null) {
            return new FabricMetadataSource(legacyMetadataPath(cache, spec), metadata);
        }
        support.resolveDownload(metadataPath, "Fabric loader metadata", metadataUri(spec), context);
        return new FabricMetadataSource(metadataPath, ProductionLaunchSupport.read(metadataPath, FabricMetadata.class));
    }

    private FabricMetadata legacyServerMetadata(CacheLayout cache, LaunchSpec spec) throws Exception {
        if (!"server".equals(spec.type())) {
            return null;
        }
        Path legacyMetadataPath = legacyMetadataPath(cache, spec);
        if (!Files.isRegularFile(legacyMetadataPath)) {
            return null;
        }
        FabricMetadata metadata = ProductionLaunchSupport.read(legacyMetadataPath, FabricMetadata.class);
        return ProductionLaunchSupport.text(metadata.launcherMainClass("server")) ? metadata : null;
    }

    private Path metadataPath(CacheLayout cache, LaunchSpec spec) {
        return support.loaderMetadata(
                cache,
                "fabric",
                spec.minecraftVersion(),
                spec.loaderVersion(),
                "server".equals(spec.type()) ? "server-loader.json" : "loader.json");
    }

    private Path legacyMetadataPath(CacheLayout cache, LaunchSpec spec) {
        return support.loaderMetadata(
                cache,
                "fabric",
                spec.minecraftVersion(),
                spec.loaderVersion(),
                "loader.json");
    }

    private static URI metadataUri(LaunchSpec spec) {
        URI baseUri = ProductionLaunchSupport.uriHint(spec, "fabricMetaBaseUri", DEFAULT_FABRIC_META_BASE_URI);
        String endpoint = "server".equals(spec.type()) ? "server/json" : "profile/json";
        return baseUri.resolve(spec.minecraftVersion() + "/" + spec.loaderVersion() + "/" + endpoint);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FabricMetadata(
            FabricArtifact loader,
            FabricArtifact intermediary,
            LauncherMeta launcherMeta,
            String mainClass,
            VersionJson.Arguments arguments,
            List<LoaderLibrary> libraries) {
        FabricMetadata {
            libraries = libraries == null ? List.of() : List.copyOf(libraries);
        }

        List<LoaderLibrary> librariesFor(String type) {
            List<LoaderLibrary> resolved = new ArrayList<>();
            if (intermediary != null && ProductionLaunchSupport.text(intermediary.maven())) {
                resolved.add(new LoaderLibrary(intermediary.maven(), intermediary.path()));
            }
            if (loader != null && ProductionLaunchSupport.text(loader.maven())) {
                resolved.add(new LoaderLibrary(loader.maven(), loader.path()));
            }
            resolved.addAll(libraries);
            if (launcherMeta != null && launcherMeta.libraries() != null) {
                resolved.addAll(launcherMeta.libraries().common());
                if ("server".equals(type)) {
                    resolved.addAll(launcherMeta.libraries().server());
                } else {
                    resolved.addAll(launcherMeta.libraries().client());
                }
            }
            return resolved;
        }

        List<Object> jvmArguments() {
            return arguments == null ? List.of() : arguments.jvm();
        }

        List<Object> gameArguments() {
            return arguments == null ? List.of() : arguments.game();
        }

        String mainClass(String type) {
            String launcherMainClass = launcherMainClass(type);
            if (ProductionLaunchSupport.text(launcherMainClass)) {
                return launcherMainClass;
            }
            return mainClass;
        }

        String launcherMainClass(String type) {
            if (launcherMeta == null || launcherMeta.mainClass() == null) {
                return null;
            }
            return "server".equals(type) ? launcherMeta.mainClass().server() : launcherMeta.mainClass().client();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FabricArtifact(String maven, String path, String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LauncherMeta(FabricMainClass mainClass, FabricLibraries libraries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FabricMainClass(String client, String server) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FabricLibraries(List<LoaderLibrary> common, List<LoaderLibrary> client, List<LoaderLibrary> server) {
        FabricLibraries {
            common = common == null ? List.of() : List.copyOf(common);
            client = client == null ? List.of() : List.copyOf(client);
            server = server == null ? List.of() : List.copyOf(server);
        }
    }

    private record FabricMetadataSource(Path path, FabricMetadata metadata) {
    }
}
