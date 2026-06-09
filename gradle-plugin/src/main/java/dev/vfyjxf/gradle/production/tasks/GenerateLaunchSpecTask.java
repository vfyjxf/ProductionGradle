package dev.vfyjxf.gradle.production.tasks;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchAuth;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchJava;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchMod;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchPaths;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSettings;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidator;
import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.mods.CurseForgeProvider;
import dev.vfyjxf.gradle.mods.ModProvider;
import dev.vfyjxf.gradle.mods.ModProviderContext;
import dev.vfyjxf.gradle.mods.ModProviderResult;
import dev.vfyjxf.gradle.mods.ModRequest;
import dev.vfyjxf.gradle.mods.ModResolutionException;
import dev.vfyjxf.gradle.mods.ModrinthProvider;
import dev.vfyjxf.gradle.production.internal.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateLaunchSpecTask extends DefaultTask {
    public GenerateLaunchSpecTask() {
        getResolutionHints().convention(Map.of());
    }

    @Input
    public abstract Property<String> getRunName();

    @Input
    public abstract Property<String> getRunType();

    @Input
    @Optional
    public abstract Property<String> getMinecraftVersion();

    @Input
    @Optional
    public abstract Property<String> getLoader();

    @Input
    @Optional
    public abstract Property<String> getLoaderVersion();

    @Input
    public abstract Property<Integer> getJavaVersion();

    @Input
    public abstract Property<String> getJavaExecutablePath();

    @Input
    public abstract Property<String> getInstanceDirPath();

    @Input
    public abstract Property<String> getWorkingDirPath();

    @Input
    public abstract Property<String> getCacheDirPath();

    @Input
    public abstract Property<String> getAssetsDirPath();

    @Input
    public abstract Property<String> getLibrariesDirPath();

    @Input
    public abstract Property<String> getNativesDirPath();

    @Input
    public abstract Property<String> getLogsDirPath();

    @Input
    public abstract ListProperty<String> getJvmArgs();

    @Input
    public abstract ListProperty<String> getGameArgs();

    @Input
    public abstract MapProperty<String, String> getEnvironment();

    @Input
    public abstract Property<String> getAuthMode();

    @Input
    public abstract Property<String> getAuthUserName();

    @Input
    @Optional
    public abstract Property<String> getAuthTokenCacheKey();

    @Input
    @Optional
    public abstract Property<String> getMainClass();

    @Input
    public abstract Property<Boolean> getEulaAccepted();

    @Input
    public abstract Property<Boolean> getGradleOffline();

    @Input
    public abstract Property<String> getGradleUserHomePath();

    @Input
    public abstract Property<Boolean> getIncludeProjectMod();

    @Input
    public abstract Property<Boolean> getIncludeRequiredDependencies();

    @Input
    public abstract Property<Boolean> getIncludeOptionalDependencies();

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getModClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getProjectModClasspath();

    @Input
    public abstract ListProperty<String> getRemoteModRequests();

    @Input
    public abstract MapProperty<String, String> getResolutionHints();

    @Input
    @Optional
    public abstract Property<String> getModrinthBaseUri();

    @Input
    @Optional
    public abstract Property<String> getCurseForgeBaseUri();

    @Input
    @Optional
    public abstract Property<String> getCurseForgeApiKey();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() throws IOException {
        LaunchSpec spec = launchSpec();
        new LaunchSpecValidator().validate(spec);
        SpecWriter.write(spec, getOutputFile().get().getAsFile());
    }

    private LaunchSpec launchSpec() {
        Path instanceDir = Path.of(getInstanceDirPath().get());
        Path workingDir = Path.of(getWorkingDirPath().get());
        Path cacheDir = Path.of(getCacheDirPath().get());
        Path assetsDir = Path.of(getAssetsDirPath().get());
        Path librariesDir = Path.of(getLibrariesDirPath().get());
        Path nativesDir = Path.of(getNativesDirPath().get());
        Path logsDir = Path.of(getLogsDirPath().get());
        return new LaunchSpec(
                1,
                getRunName().get(),
                getRunType().get(),
                getMinecraftVersion().getOrElse(""),
                getLoader().getOrElse("vanilla"),
                getLoaderVersion().getOrNull(),
                new LaunchPaths(
                        instanceDir,
                        workingDir,
                        cacheDir,
                        assetsDir,
                        librariesDir,
                        nativesDir,
                        logsDir),
                new LaunchJava(Path.of(getJavaExecutablePath().get()), getJavaVersion().get()),
                new LaunchAuth(getAuthMode().get(), getAuthUserName().get(), getAuthTokenCacheKey().getOrNull()),
                mods(),
                new LaunchSettings(
                        getJvmArgs().get(),
                        getGameArgs().get(),
                        getEnvironment().get(),
                        getMainClass().getOrNull(),
                        getEulaAccepted().get()),
                Map.copyOf(getResolutionHints().getOrElse(Map.of())),
                Map.of(
                        "offline", getGradleOffline().get(),
                        "userHome", getGradleUserHomePath().get()));
    }

    private List<LaunchMod> mods() {
        List<LaunchMod> mods = new ArrayList<>(gradleArtifactMods());
        mods.addAll(remoteMods());
        return mods.stream()
                .distinct()
                .sorted(Comparator.comparing(mod -> mod.file().toAbsolutePath().normalize().toString()))
                .toList();
    }

    private List<LaunchMod> gradleArtifactMods() {
        return Stream.concat(
                        getIncludeProjectMod().getOrElse(true)
                                ? getProjectModClasspath().getFiles().stream()
                                : Stream.empty(),
                        getModClasspath().getFiles().stream())
                .distinct()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .map(file -> new LaunchMod("gradle", file.getName(), null, file.toPath(), Map.of()))
                .toList();
    }

    private List<LaunchMod> remoteMods() {
        List<String> requests = getRemoteModRequests().getOrElse(List.of());
        if (requests.isEmpty()) {
            return List.of();
        }
        ModProviderContext context = ModProviderContext.builder()
                .minecraftVersion(getMinecraftVersion().getOrElse(""))
                .loader(getLoader().getOrElse("vanilla"))
                .cacheLayout(CacheLayout.under(Path.of(getCacheDirPath().get())))
                .includeRequiredDependencies(getIncludeRequiredDependencies().getOrElse(true))
                .includeOptionalDependencies(getIncludeOptionalDependencies().getOrElse(false))
                .curseForgeApiKey(getCurseForgeApiKey().getOrNull())
                .offline(getGradleOffline().getOrElse(false))
                .build();
        List<ModProvider> providers = List.of(
                getModrinthBaseUri().isPresent()
                        ? new ModrinthProvider(URI.create(getModrinthBaseUri().get()))
                        : new ModrinthProvider(),
                getCurseForgeBaseUri().isPresent()
                        ? new CurseForgeProvider(URI.create(getCurseForgeBaseUri().get()))
                        : new CurseForgeProvider());
        List<LaunchMod> resolvedMods = new ArrayList<>();
        for (String notation : requests) {
            ModRequest request = remoteRequest(notation);
            ModProvider provider = providers.stream()
                    .filter(candidate -> candidate.supports(request))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No provider supports remote mod request: " + notation));
            try {
                ModProviderResult result = provider.resolve(context, request);
                for (ModProviderResult.ModFile mod : result.mods()) {
                    resolvedMods.add(new LaunchMod(mod.source(), mod.id(), mod.version(), mod.file(), mod.metadata()));
                }
            } catch (ModResolutionException exception) {
                throw new IllegalStateException("Failed to resolve remote mod request " + notation + ": "
                        + exception.getMessage(), exception);
            }
        }
        return resolvedMods;
    }

    private static ModRequest remoteRequest(String notation) {
        List<String> parts = splitNotation(notation);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Remote mod request notation is empty.");
        }
        return switch (parts.getFirst()) {
            case "modrinth-project" -> ModRequest.modrinthProject(requiredPart(parts, 1, notation), blankToNull(part(parts, 2)));
            case "modrinth-version" -> ModRequest.modrinthVersion(requiredPart(parts, 1, notation));
            case "curseforge-file" -> ModRequest.curseForgeFile(
                    Integer.parseInt(requiredPart(parts, 1, notation)),
                    Integer.parseInt(requiredPart(parts, 2, notation)));
            default -> throw new IllegalArgumentException("Unsupported remote mod request notation: " + notation);
        };
    }

    private static String requiredPart(List<String> parts, int index, String notation) {
        String value = part(parts, index);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid remote mod request notation: " + notation);
        }
        return value;
    }

    private static String part(List<String> parts, int index) {
        return index < parts.size() ? parts.get(index) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> splitNotation(String notation) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < notation.length(); index++) {
            char character = notation.charAt(index);
            if (escaped) {
                current.append(character == 'p' ? '|' : character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        parts.add(current.toString());
        return parts;
    }
}
