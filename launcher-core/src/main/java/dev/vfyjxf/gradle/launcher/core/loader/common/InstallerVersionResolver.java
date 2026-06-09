package dev.vfyjxf.gradle.launcher.core.loader.common;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.loader.LoaderResolver;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class InstallerVersionResolver implements LoaderResolver {
    protected final ProductionLaunchSupport support;
    private final String loader;
    private final String displayName;

    protected InstallerVersionResolver(String loader, String displayName) {
        this(new ProductionLaunchSupport(), loader, displayName);
    }

    protected InstallerVersionResolver(ProductionLaunchSupport support, String loader, String displayName) {
        this.support = support;
        this.loader = loader;
        this.displayName = displayName;
    }

    @Override
    public boolean supports(String loader) {
        return this.loader.equals(loader);
    }

    @Override
    public PreparedLaunch prepare(LaunchSpec spec) throws Exception {
        return prepare(LaunchContext.directCli(spec, false));
    }

    @Override
    public PreparedLaunch prepare(LaunchContext context) throws Exception {
        LaunchSpec spec = context.spec();
        support.validate(spec, loader);
        CacheLayout cache = support.cacheLayout(spec);
        VersionJson minecraftVersion = support.resolveMinecraftVersion(context, cache);
        Path loaderVersionPath = support.loaderMetadata(
                cache,
                loader,
                spec.minecraftVersion(),
                spec.loaderVersion(),
                "version.json");
        String coordinate = installerCoordinate(spec);
        URI mavenBaseUri = installerMavenBaseUri(spec);
        Path installerPath = ensureInstallerVersionJson(spec, loaderVersionPath, context, coordinate, mavenBaseUri);
        VersionJson loaderVersion = ProductionLaunchSupport.read(
                loaderVersionPath,
                VersionJson.class,
                displayName + " installer version metadata");
        afterInstallerMetadata(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                loaderVersionPath,
                installerPath,
                coordinate,
                mavenBaseUri);

        PreparedLaunch metadataLaunch = beforeClasspathPreparedLaunch(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                loaderVersionPath,
                installerPath,
                coordinate,
                mavenBaseUri);
        if (metadataLaunch != null) {
            return metadataLaunch;
        }

        List<Path> classpath = classpath(context, cache, minecraftVersion, loaderVersion, loaderVersionPath);
        Map<String, String> placeholders = support.placeholders(context, cache, minecraftVersion, classpath);
        List<String> jvmArgs = new ArrayList<>();
        if ("client".equals(spec.type())) {
            jvmArgs.addAll(support.minecraftJvmArgs(minecraftVersion, placeholders));
        }
        jvmArgs.addAll(support.minecraftJvmArgs(loaderVersion, placeholders));
        jvmArgs.addAll(support.interpolate(spec.launch().jvmArgs(), placeholders));
        List<String> gameArgs = new ArrayList<>();
        if ("client".equals(spec.type())) {
            gameArgs.addAll(support.minecraftGameArgs(minecraftVersion, placeholders));
        }
        gameArgs.addAll(support.minecraftGameArgs(loaderVersion, placeholders));
        gameArgs.addAll(support.interpolate(spec.launch().gameArgs(), placeholders));

        String mainClass = mainClass(spec, loaderVersion, loaderVersionPath);
        PreparedLaunch preparedLaunch = new PreparedLaunch(classpath, jvmArgs, gameArgs, mainClass);
        return finalizePreparedLaunch(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                loaderVersionPath,
                installerPath,
                coordinate,
                mavenBaseUri,
                classpath,
                preparedLaunch);
    }

    protected void afterInstallerMetadata(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI installerMavenBaseUri) throws Exception {
    }

    protected PreparedLaunch beforeClasspathPreparedLaunch(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI installerMavenBaseUri) throws Exception {
        return null;
    }

    protected PreparedLaunch finalizePreparedLaunch(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI installerMavenBaseUri,
            List<Path> classpath,
            PreparedLaunch preparedLaunch) throws Exception {
        return preparedLaunch;
    }

    private List<Path> classpath(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath) {
        LaunchSpec spec = context.spec();
        List<Path> classpath = new ArrayList<>();
        if ("client".equals(spec.type())) {
            support.resolveAssets(cache, minecraftVersion, context);
            classpath.addAll(support.resolveMinecraftClasspath(cache, minecraftVersion, context));
        }
        Path minecraftJar = support.minecraftJar(spec, cache, minecraftVersion);
        support.ensureMinecraftJar(minecraftJar, "Minecraft " + spec.type() + " jar", minecraftVersion, spec.type(), context);
        classpath.add(minecraftJar);

        URI mavenBaseUri = installerMavenBaseUri(spec);
        for (Path libraryPath : support.resolveLibraries(
                cache,
                loaderVersion,
                loaderVersionPath,
                displayName + " loader library",
                mavenBaseUri,
                context)) {
            if (!classpath.contains(libraryPath)) {
                classpath.add(libraryPath);
            }
        }
        return classpath;
    }

    private String mainClass(LaunchSpec spec, VersionJson loaderVersion, Path loaderVersionPath) {
        if (ProductionLaunchSupport.text(spec.launch().mainClass())) {
            return spec.launch().mainClass();
        }
        if (ProductionLaunchSupport.text(loaderVersion.mainClass())) {
            return loaderVersion.mainClass();
        }
        throw new IllegalStateException("cache metadata missing " + displayName + " main class in " + loaderVersionPath);
    }

    protected abstract URI installerMavenBaseUri(LaunchSpec spec);

    protected abstract String installerCoordinate(LaunchSpec spec);

    private Path ensureInstallerVersionJson(
            LaunchSpec spec,
            Path loaderVersionPath,
            LaunchContext context,
            String coordinate,
            URI installerMavenBaseUri) throws Exception {
        Path installerPath = loaderVersionPath.getParent().resolve(installerFileName(coordinate, loaderVersionPath));
        if (Files.isRegularFile(loaderVersionPath)) {
            return installerPath;
        }
        URI installerUri = installerMavenBaseUri.resolve(support.mavenArtifactPath(coordinate, loaderVersionPath));
        if (Files.isRegularFile(installerPath)) {
            support.extractInstallerVersionJson(installerPath, loaderVersionPath, displayName + " installer");
            return installerPath;
        }
        if (context.offline()) {
            support.requireCachedFile(
                    loaderVersionPath,
                    displayName + " installer version metadata",
                    context,
                    installerUri);
            return installerPath;
        }
        support.resolveDownload(installerPath, displayName + " installer", installerUri, context);
        support.extractInstallerVersionJson(installerPath, loaderVersionPath, displayName + " installer");
        return installerPath;
    }

    private String installerFileName(String coordinate, Path metadataPath) {
        return Path.of(support.mavenArtifactPath(coordinate, metadataPath)).getFileName().toString();
    }
}
