package dev.vfyjxf.gradle.launcher.core.loader.neoforge;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.loader.PreparedLaunch;
import dev.vfyjxf.gradle.launcher.core.loader.common.InstallerInstallProfileRunner;
import dev.vfyjxf.gradle.launcher.core.loader.common.InstallerVersionResolver;
import dev.vfyjxf.gradle.launcher.core.loader.common.ProductionLaunchSupport;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NeoForgeResolver extends InstallerVersionResolver {
    private static final URI DEFAULT_NEOFORGE_MAVEN_BASE_URI = URI.create("https://maven.neoforged.net/releases/");
    private final InstallerInstallProfileRunner installProfileRunner;

    public NeoForgeResolver() {
        super("neoforge", "NeoForge");
        this.installProfileRunner = new InstallerInstallProfileRunner(support, "NeoForge");
    }

    @Override
    protected URI installerMavenBaseUri(LaunchSpec spec) {
        return ProductionLaunchSupport.uriHint(spec, "neoforgeMavenBaseUri", DEFAULT_NEOFORGE_MAVEN_BASE_URI);
    }

    @Override
    protected String installerCoordinate(LaunchSpec spec) {
        return "net.neoforged:neoforge:%s:installer".formatted(artifactVersion(spec.loaderVersion()));
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
                !hasNeoForgeUniversalLibrary(loaderVersion));
    }

    @Override
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
        if (!"server".equals(context.spec().type())) {
            return preparedLaunch;
        }
        List<String> serverArguments = installProfileRunner.resolveServerArguments(cache, installerCoordinate);
        if (serverArguments.isEmpty()) {
            return preparedLaunch;
        }
        int mainClassIndex = serverArguments.indexOf(preparedLaunch.mainClass());
        if (mainClassIndex < 0) {
            return preparedLaunch;
        }
        Map<String, String> placeholders = support.placeholders(context, cache, minecraftVersion, classpath);
        List<String> jvmArgs = new ArrayList<>(serverArguments.subList(0, mainClassIndex));
        jvmArgs.addAll(support.interpolate(context.spec().launch().jvmArgs(), placeholders));
        List<String> gameArgs = new ArrayList<>(serverArguments.subList(mainClassIndex + 1, serverArguments.size()));
        gameArgs.addAll(support.interpolate(context.spec().launch().gameArgs(), placeholders));
        return new PreparedLaunch(List.of(), jvmArgs, gameArgs, preparedLaunch.mainClass());
    }

    private static String artifactVersion(String loaderVersion) {
        int mappedSuffix = loaderVersion.indexOf("_mapped_");
        if (mappedSuffix >= 0) {
            return loaderVersion.substring(0, mappedSuffix);
        }
        return loaderVersion;
    }

    private static boolean hasNeoForgeUniversalLibrary(VersionJson loaderVersion) {
        for (VersionJson.Library library : loaderVersion.libraries()) {
            if (library.name() != null
                    && library.name().startsWith("net.neoforged:neoforge:")
                    && library.name().contains(":universal")) {
                return true;
            }
        }
        return false;
    }
}
