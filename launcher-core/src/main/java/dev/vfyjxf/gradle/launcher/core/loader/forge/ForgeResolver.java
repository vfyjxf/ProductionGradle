package dev.vfyjxf.gradle.launcher.core.loader.forge;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.common.InstallerInstallProfileRunner;
import dev.vfyjxf.gradle.launcher.core.loader.common.InstallerVersionResolver;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForgeResolver extends InstallerVersionResolver {
    private static final URI DEFAULT_FORGE_MAVEN_BASE_URI = URI.create("https://maven.minecraftforge.net/");
    private final InstallerInstallProfileRunner installProfileRunner;

    public ForgeResolver() {
        super("forge", "Forge");
        this.installProfileRunner = new InstallerInstallProfileRunner(support, "Forge");
    }

    @Override
    protected URI installerMavenBaseUri(LaunchSpec spec) {
        return ProductionLaunchSupport.uriHint(spec, "forgeMavenBaseUri", DEFAULT_FORGE_MAVEN_BASE_URI);
    }

    @Override
    protected String installerCoordinate(LaunchSpec spec) {
        return "net.minecraftforge:forge:%s-%s:installer"
                .formatted(spec.minecraftVersion(), artifactVersion(spec.loaderVersion()));
    }

    @Override
    protected void afterInstallerMetadata(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI installerMavenBaseUri) throws Exception {
        installProfileRunner.install(
                context,
                cache,
                minecraftVersion,
                loaderVersion,
                loaderVersionPath,
                installerPath,
                installerCoordinate,
                installerMavenBaseUri,
                false);
    }

    @Override
    protected PreparedLaunch beforeClasspathPreparedLaunch(
            LaunchContext context,
            CacheLayout cache,
            VersionJson minecraftVersion,
            VersionJson loaderVersion,
            Path loaderVersionPath,
            Path installerPath,
            String installerCoordinate,
            URI installerMavenBaseUri) throws Exception {
        if (!"server".equals(context.spec().type())) {
            return null;
        }
        List<String> serverArguments = installProfileRunner.resolveServerArguments(cache, installerCoordinate);
        if (serverArguments.isEmpty()) {
            return null;
        }
        Map<String, String> placeholders = support.placeholders(context, cache, minecraftVersion, List.of());
        List<String> jvmArgs = new ArrayList<>(support.interpolate(context.spec().launch().jvmArgs(), placeholders));
        jvmArgs.addAll(resolveForgeServerArguments(cache, installerCoordinate, serverArguments));
        List<String> gameArgs = new ArrayList<>(support.interpolate(context.spec().launch().gameArgs(), placeholders));
        return new PreparedLaunch(List.of(), jvmArgs, gameArgs, "");
    }

    private static String artifactVersion(String loaderVersion) {
        int mappedSuffix = loaderVersion.indexOf("_mapped_");
        if (mappedSuffix >= 0) {
            return loaderVersion.substring(0, mappedSuffix);
        }
        return loaderVersion;
    }

    private static List<String> resolveForgeServerArguments(
            CacheLayout cache,
            String installerCoordinate,
            List<String> serverArguments) {
        List<String> resolved = new ArrayList<>(serverArguments.size());
        boolean resolveJar = false;
        for (String argument : serverArguments) {
            if (resolveJar && !Path.of(argument).isAbsolute()) {
                resolved.add(cache.libraries()
                        .resolve("net")
                        .resolve("minecraftforge")
                        .resolve("forge")
                        .resolve(forgeVersionDirectory(installerCoordinate))
                        .resolve(argument)
                        .toString());
            } else {
                resolved.add(resolveForgeServerArgument(cache, argument));
            }
            resolveJar = "-jar".equals(argument);
        }
        return resolved;
    }

    private static String forgeVersionDirectory(String installerCoordinate) {
        String[] parts = installerCoordinate.split(":");
        if (parts.length < 3) {
            throw new IllegalStateException("invalid Forge installer coordinate: " + installerCoordinate);
        }
        return parts[2];
    }

    private static String resolveForgeServerArgument(CacheLayout cache, String argument) {
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
}
