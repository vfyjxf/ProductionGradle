package dev.vfyjxf.gradle.production;

import dev.vfyjxf.gradle.adapters.DevelopmentEnvironment;
import dev.vfyjxf.gradle.adapters.DevelopmentEnvironmentService;
import dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain;
import dev.vfyjxf.gradle.production.dsl.ModSetSpec;
import dev.vfyjxf.gradle.production.dsl.ProductionExtension;
import dev.vfyjxf.gradle.production.dsl.ProductionRunSpec;
import dev.vfyjxf.gradle.production.internal.ConfigurationNames;
import dev.vfyjxf.gradle.production.internal.ToolchainResolver;
import dev.vfyjxf.gradle.production.tasks.GenerateProductionLauncherBridgeTask;
import dev.vfyjxf.gradle.production.tasks.GenerateLaunchSpecTask;
import dev.vfyjxf.gradle.production.tasks.LauncherExecTask;
import dev.vfyjxf.gradle.production.tasks.PrintLaunchSpecTask;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.jetbrains.gradle.ext.Application;
import org.jetbrains.gradle.ext.BeforeRunTask;
import org.jetbrains.gradle.ext.Gradle;
import org.jetbrains.gradle.ext.IdeaExtPlugin;
import org.jetbrains.gradle.ext.ProjectSettings;
import org.jetbrains.gradle.ext.RunConfiguration;
import org.jetbrains.gradle.ext.RunConfigurationContainer;

public class ProductionGradlePlugin implements Plugin<Project> {
    private static final String TASK_GROUP = "production";
    private static final String LAUNCHER_SOURCE_SET_NAME = "productionLauncher";
    private static final String LAUNCHER_BRIDGE_CLASS_NAME =
            "dev.vfyjxf.gradle.production.bridge.ProductionLauncherBridge";
    private static final Set<String> RESERVED_RUN_TASK_SUFFIXES = Set.of("Run");

    @Override
    public void apply(Project project) {
        ProductionExtension extension = project.getExtensions()
                .create("production", ProductionExtension.class, project);
        configureExtensionDefaults(project, extension);
        AtomicReference<DevelopmentEnvironment> detectedEnvironment = new AtomicReference<>();
        project.afterEvaluate(ignored -> detectedEnvironment.set(extension.getAutoDetect().getOrElse(false)
                ? new DevelopmentEnvironmentService().detect(project).orElse(null)
                : null));
        Provider<DevelopmentEnvironment> detectedEnvironmentProvider = project.provider(detectedEnvironment::get);
        Map<String, String> runNamesByTaskSuffix = new LinkedHashMap<>();
        List<IdeaRunRegistration> ideaRuns = new ArrayList<>();
        configureIntellijSyncIntegration(project);
        if (isIntellijSync()) {
            project.getGradle().projectsEvaluated(gradle -> configureIdeaRuns(ideaRuns));
        }

        extension.getRuns().configureEach(run -> {
            configureRunDefaults(extension, run);
            configureAutoDetectedRunDefaults(run, detectedEnvironmentProvider);
            String formattedRunName = validateAndFormatRunName(run, runNamesByTaskSuffix);
            Configuration modClasspath = configureModClasspath(project, run);
            Configuration projectModClasspath = configureProjectModClasspath(project, run);
            TaskProvider<GenerateLaunchSpecTask> generateLaunchSpec = registerRunTasks(
                    project,
                    extension,
                    run,
                    formattedRunName,
                    modClasspath,
                    projectModClasspath,
                    detectedEnvironmentProvider);
            ideaRuns.add(new IdeaRunRegistration(project, extension, formattedRunName, generateLaunchSpec));
        });

        extension.getRuns().maybeCreate("client").getType().convention("client");
        extension.getRuns().maybeCreate("server").getType().convention("server");
        registerValidateProductionRunAlias(project);
        project.getPlugins().withId("java", plugin -> configureProductionLauncherBridge(project));

    }

    private static void configureExtensionDefaults(Project project, ProductionExtension extension) {
        extension.getAutoDetect().convention(true);
        extension.getInstanceDir().convention(project.getLayout().getProjectDirectory().dir("run-production"));
        File gradleUserHome = project.getGradle().getGradleUserHomeDir();
        extension.getCacheDir()
                .convention(project.getLayout()
                        .dir(project.provider(() -> new File(gradleUserHome, "caches/production-gradle"))));
        extension.getIdea().getEnabled().convention(true);
        extension.getIdea().getMode().convention("application");
        extension.getIdea().getOverwrite().convention(false);
    }

    private static void configureRunDefaults(ProductionExtension extension, ProductionRunSpec run) {
        run.getType().convention(run.getName());
        run.getInstanceDir().convention(extension.getInstanceDir().map(directory -> directory.dir(run.getName())));
        run.getWorkingDir().convention(run.getInstanceDir());
        run.getCacheDir().convention(extension.getCacheDir());
        run.getJvmArgs().convention(Collections.emptyList());
        run.getGameArgs().convention(Collections.emptyList());
        run.getEnvironment().convention(Collections.emptyMap());
        run.getEula().convention(false);
        run.getUserName().convention("DevPlayer");
        run.getMicrosoftAuth().convention(false);

        ModSetSpec mods = run.getMods();
        mods.getIncludeProject().convention(true);
        mods.getIncludeRequiredDependencies().convention(true);
        mods.getIncludeOptionalDependencies().convention(false);
    }

    private static void configureAutoDetectedRunDefaults(
            ProductionRunSpec run,
            Provider<DevelopmentEnvironment> detectedEnvironment) {
        run.getMinecraftVersion().convention(detectedEnvironment.map(DevelopmentEnvironment::minecraftVersion)
                .filter(ProductionGradlePlugin::hasText));
        run.getLoader().convention(detectedEnvironment.map(DevelopmentEnvironment::loader)
                .filter(ProductionGradlePlugin::hasText));
        run.getLoaderVersion().convention(detectedEnvironment.map(DevelopmentEnvironment::loaderVersion)
                .filter(ProductionGradlePlugin::hasText));
    }

    private static Configuration configureModClasspath(Project project, ProductionRunSpec run) {
        String configurationName = ConfigurationNames.modClasspath(run.getName());
        run.getMods().setConfigurationName(configurationName);

        Configuration configuration = project.getConfigurations().maybeCreate(configurationName);
        configureResolvableJarClasspath(project, configuration);
        return configuration;
    }

    private static Configuration configureProjectModClasspath(Project project, ProductionRunSpec run) {
        String configurationName = ConfigurationNames.projectModClasspath(run.getName());
        Configuration configuration = project.getConfigurations().maybeCreate(configurationName);
        configureResolvableJarClasspath(project, configuration);
        return configuration;
    }

    private static void configureResolvableJarClasspath(Project project, Configuration configuration) {
        ObjectFactory objects = project.getObjects();
        configuration.setCanBeResolved(true);
        configuration.setCanBeConsumed(false);
        configuration.setVisible(false);
        configuration.getAttributes().attribute(
                Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
        configuration.getAttributes().attribute(
                Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
        configuration.getAttributes().attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.JAR));
    }

    private static TaskProvider<GenerateLaunchSpecTask> registerRunTasks(
            Project project,
            ProductionExtension extension,
            ProductionRunSpec run,
            String runName,
            Configuration modClasspath,
            Configuration projectModClasspath,
            Provider<DevelopmentEnvironment> detectedEnvironment) {
        ToolchainResolver toolchains = project.getObjects().newInstance(ToolchainResolver.class);
        TaskProvider<GenerateLaunchSpecTask> generateLaunchSpec = project.getTasks().register(
                "generateProduction" + runName + "LaunchSpec",
                GenerateLaunchSpecTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Generates the " + run.getName() + " production launch spec.");
                    task.getRunName().set(run.getName());
                    task.getRunType().set(run.getType());
                    task.getMinecraftVersion().set(run.getMinecraftVersion());
                    task.getLoader().set(run.getLoader());
                    task.getLoaderVersion().set(run.getLoaderVersion());
                    task.getJavaVersion().set(run.getJavaVersion().orElse(21));
                    task.getJavaExecutablePath().set(toolchains.resolveJavaExecutable(run)
                            .map(file -> file.getAsFile().getAbsolutePath()));
                    task.getInstanceDirPath().set(run.getInstanceDir()
                            .map(directory -> directory.getAsFile().getAbsolutePath()));
                    task.getWorkingDirPath().set(run.getWorkingDir()
                            .map(directory -> directory.getAsFile().getAbsolutePath()));
                    task.getCacheDirPath().set(run.getCacheDir()
                            .map(directory -> directory.getAsFile().getAbsolutePath()));
                    task.getAssetsDirPath().set(run.getCacheDir()
                            .map(directory -> directory.dir("assets").getAsFile().getAbsolutePath()));
                    task.getLibrariesDirPath().set(run.getCacheDir()
                            .map(directory -> directory.dir("libraries").getAsFile().getAbsolutePath()));
                    task.getNativesDirPath().set(run.getInstanceDir()
                            .map(directory -> directory.dir("natives").getAsFile().getAbsolutePath()));
                    task.getLogsDirPath().set(run.getInstanceDir()
                            .map(directory -> directory.dir("logs").getAsFile().getAbsolutePath()));
                    task.getJvmArgs().set(run.getJvmArgs());
                    task.getGameArgs().set(run.getGameArgs());
                    task.getEnvironment().set(run.getEnvironment());
                    task.getAuthMode().set(run.getMicrosoftAuth().map(microsoft ->
                            microsoft ? "microsoft" : "offline"));
                    task.getAuthUserName().set(run.getUserName());
                    task.getMainClass().set(run.getMainClass());
                    task.getEulaAccepted().set(run.getEula());
                    task.getGradleOffline().set(project.getGradle().getStartParameter().isOffline());
                    task.getGradleUserHomePath().set(project.provider(() ->
                            project.getGradle().getGradleUserHomeDir().getAbsolutePath()));
                    task.getIncludeProjectMod().set(run.getMods().getIncludeProject());
                    task.getIncludeRequiredDependencies().set(run.getMods().getIncludeRequiredDependencies());
                    task.getIncludeOptionalDependencies().set(run.getMods().getIncludeOptionalDependencies());
                    task.getModClasspath().from(modClasspath);
                    configureIncludedProjectModArtifact(project, extension, task, projectModClasspath, detectedEnvironment);
                    task.getRemoteModRequests().set(run.getMods().getRemoteRequestNotations());
                    task.getResolutionHints().putAll(detectedEnvironment
                            .map(DevelopmentEnvironment::resolutionHints)
                            .orElse(Map.of()));
                    configureResolutionHints(project, task);
                    task.getModrinthBaseUri().set(project.getProviders().gradleProperty("production.modrinthBaseUri"));
                    task.getCurseForgeBaseUri().set(project.getProviders().gradleProperty("production.curseforgeBaseUri"));
                    task.getCurseForgeApiKey().set(project.getProviders().gradleProperty("production.curseforgeApiKey")
                            .orElse(project.getProviders().environmentVariable("CURSEFORGE_API_KEY")));
                    task.getOutputFile().set(project.getLayout()
                            .getBuildDirectory()
                            .file("production-gradle/specs/" + run.getName() + "/launch-spec.json"));
                });
        registerLauncherExecTask(
                project,
                "validateProduction" + runName,
                "Validates the " + run.getName() + " production launch spec.",
                "validate",
                generateLaunchSpec);
        registerLauncherExecTask(
                project,
                "prepareProduction" + runName,
                "Prepares the " + run.getName() + " production run.",
                "prepare",
                generateLaunchSpec);
        registerLauncherExecTask(
                project,
                "runProduction" + runName,
                "Runs the " + run.getName() + " production launch.",
                "run",
                generateLaunchSpec);
        project.getTasks().register(
                "printProduction" + runName + "LaunchSpec",
                PrintLaunchSpecTask.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription("Prints the " + run.getName() + " production launch spec file path.");
                    task.dependsOn(generateLaunchSpec);
                    task.getSpecFile().set(generateLaunchSpec.flatMap(GenerateLaunchSpecTask::getOutputFile));
                });
        registerLauncherExecTask(
                project,
                "printProduction" + runName + "Command",
                "Prints the " + run.getName() + " production launch command.",
                "print-command",
                generateLaunchSpec);
        registerLauncherExecTask(
                project,
                "printProduction" + runName + "Classpath",
                "Prints the " + run.getName() + " production classpath.",
                "print-classpath",
                generateLaunchSpec);
        return generateLaunchSpec;
    }

    private static void registerValidateProductionRunAlias(Project project) {
        project.getTasks().register("validateProductionRun", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Validates the default client production launch spec.");
            task.dependsOn("validateProductionClient");
        });
    }

    private static void configureResolutionHints(Project project, GenerateLaunchSpecTask task) {
        putResolutionHint(project, task, "minecraftVersionManifestUri", "production.minecraftVersionManifestUri");
        putResolutionHint(project, task, "minecraftAssetBaseUri", "production.minecraftAssetBaseUri");
        putResolutionHint(project, task, "fabricMetaBaseUri", "production.fabricMetaBaseUri");
        putResolutionHint(project, task, "fabricMavenBaseUri", "production.fabricMavenBaseUri");
        putResolutionHint(project, task, "forgeMavenBaseUri", "production.forgeMavenBaseUri");
        putResolutionHint(project, task, "neoforgeMavenBaseUri", "production.neoforgeMavenBaseUri");
    }

    private static void putResolutionHint(
            Project project,
            GenerateLaunchSpecTask task,
            String key,
            String propertyName) {
        String value = project.getProviders().gradleProperty(propertyName).getOrNull();
        if (hasText(value)) {
            task.getResolutionHints().put(key, value);
        }
    }

    private static void configureIncludedProjectModArtifact(
            Project project,
            ProductionExtension extension,
            GenerateLaunchSpecTask task,
            Configuration projectModClasspath,
            Provider<DevelopmentEnvironment> detectedEnvironment) {
        project.getPlugins().withId("java", plugin -> {
            String artifactTaskName = projectModArtifactTaskName(project, extension, detectedEnvironment);
            if (artifactTaskName == null) {
                artifactTaskName = "jar";
            }
            TaskProvider<? extends AbstractArchiveTask> artifactTask =
                    project.getTasks().named(artifactTaskName, AbstractArchiveTask.class);
            task.getProjectModClasspath().setFrom(artifactTask.flatMap(AbstractArchiveTask::getArchiveFile));
        });
    }

    private static String projectModArtifactTaskName(
            Project project,
            ProductionExtension extension,
            Provider<DevelopmentEnvironment> detectedEnvironment) {
        if (extension.getAutoDetect().getOrElse(false)) {
            String detectedTaskPath = java.util.Optional.ofNullable(detectedEnvironment.getOrNull())
                    .flatMap(DevelopmentEnvironment::fallbackArtifactTaskPath)
                    .orElse(null);
            if (hasText(detectedTaskPath)) {
                Task detectedTask = project.getTasks().findByPath(detectedTaskPath);
                if (detectedTask instanceof AbstractArchiveTask && !"jar".equals(detectedTask.getName())) {
                    return detectedTask.getName();
                }
            }
        }
        return null;
    }

    private static TaskProvider<LauncherExecTask> registerLauncherExecTask(
            Project project,
            String name,
            String description,
            String command,
            TaskProvider<GenerateLaunchSpecTask> generateLaunchSpec) {
        return project.getTasks().register(name, LauncherExecTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(description);
            task.dependsOn(generateLaunchSpec);
            task.getCommand().set(command);
            task.getLauncherClasspath().from(launcherClasspath());
            task.getSpecFile().set(generateLaunchSpec.flatMap(GenerateLaunchSpecTask::getOutputFile));
        });
    }

    private static void configureProductionLauncherBridge(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet sourceSet = sourceSets.maybeCreate(LAUNCHER_SOURCE_SET_NAME);
        TaskProvider<GenerateProductionLauncherBridgeTask> generateBridge = project.getTasks().register(
                "generateProductionLauncherBridge",
                GenerateProductionLauncherBridgeTask.class,
                task -> task.getOutputDirectory().set(project.getLayout()
                        .getBuildDirectory()
                        .dir("generated/sources/productionLauncher/java")));

        sourceSet.getJava().srcDir(generateBridge.flatMap(GenerateProductionLauncherBridgeTask::getOutputDirectory));
        project.getTasks().named(sourceSet.getCompileJavaTaskName()).configure(task -> task.dependsOn(generateBridge));
        project.getDependencies().add(sourceSet.getRuntimeOnlyConfigurationName(), project.files(launcherClasspath()));
    }

    private static void configureIdeaRuns(List<IdeaRunRegistration> registrations) {
        for (IdeaRunRegistration registration : registrations) {
            Project project = registration.project();
            ProductionExtension extension = registration.extension();
            if (!extension.getIdea().getEnabled().get()) {
                continue;
            }

            RunConfigurationContainer runConfigurations = intellijRunConfigurations(project);
            if (runConfigurations == null) {
                continue;
            }

            String mode = extension.getIdea().getMode().get().toLowerCase(Locale.ROOT);
            String configurationName = ideaConfigurationName(project, registration.runName());
            removeExistingIdeaRunIfNeeded(runConfigurations, configurationName, extension.getIdea().getOverwrite().get());
            if ("gradle".equals(mode)) {
                configureGradleIdeaRun(project, runConfigurations, configurationName, registration.runName());
            } else if ("application".equals(mode)) {
                configureApplicationIdeaRun(project, runConfigurations, configurationName, registration.generateLaunchSpec());
            } else {
                throw new GradleException(
                        "Unsupported IDEA run configuration mode '" + extension.getIdea().getMode().get()
                                + "'. Expected 'gradle' or 'application'.");
            }
        }
    }

    private static void configureIntellijSyncIntegration(Project project) {
        if (!isIntellijSync()) {
            return;
        }
        Project rootProject = project.getRootProject();
        rootProject.getPlugins().apply(IdeaPlugin.class);
        rootProject.getPlugins().apply(IdeaExtPlugin.class);
    }

    private static boolean isIntellijSync() {
        return Boolean.getBoolean("idea.sync.active");
    }

    private static RunConfigurationContainer intellijRunConfigurations(Project project) {
        Project rootProject = project.getRootProject();
        IdeaModel ideaModel = rootProject.getExtensions().findByType(IdeaModel.class);
        if (ideaModel == null || ideaModel.getProject() == null) {
            return null;
        }
        ProjectSettings settings = ((ExtensionAware) ideaModel.getProject())
                .getExtensions()
                .findByType(ProjectSettings.class);
        if (settings == null) {
            return null;
        }
        return ((ExtensionAware) settings).getExtensions().findByType(RunConfigurationContainer.class);
    }

    private static void removeExistingIdeaRunIfNeeded(
            RunConfigurationContainer runConfigurations,
            String configurationName,
            boolean overwrite) {
        RunConfiguration existing = runConfigurations.findByName(configurationName);
        if (existing == null) {
            return;
        }
        if (!overwrite) {
            throw new GradleException("Refusing to replace existing IDEA run configuration '" + configurationName
                    + "'. Set production.idea.overwrite = true to replace it.");
        }
        runConfigurations.remove(existing);
    }

    private static void configureGradleIdeaRun(
            Project project,
            RunConfigurationContainer runConfigurations,
            String configurationName,
            String runName) {
        Gradle configuration = runConfigurations.create(configurationName, Gradle.class);
        configuration.setProjectPath(project.getProjectDir().getAbsolutePath());
        configuration.setTaskNames(List.of("runProduction" + runName));
        configuration.setScriptParameters("");
        configuration.setJvmArgs("");
    }

    private static void configureApplicationIdeaRun(
            Project project,
            RunConfigurationContainer runConfigurations,
            String configurationName,
            TaskProvider<GenerateLaunchSpecTask> generateLaunchSpec) {
        GenerateLaunchSpecTask generateLaunchSpecTask = generateLaunchSpec.get();
        Application configuration = runConfigurations.create(configurationName, Application.class);
        configuration.setModuleName(intellijModuleName(project, LAUNCHER_SOURCE_SET_NAME));
        configuration.setMainClass(LAUNCHER_BRIDGE_CLASS_NAME);
        configuration.setWorkingDirectory(project.getProjectDir().getAbsolutePath());
        configuration.setProgramParameters("run --spec "
                + quoteIfNeeded(generateLaunchSpecTask.getOutputFile().get().getAsFile().getAbsolutePath()));
        TaskProvider<Task> productionLauncherClasses = project.getTasks().named(LAUNCHER_SOURCE_SET_NAME + "Classes");
        configuration.getBeforeRun().add(new GradleTasksBeforeRun(
                "Prepare",
                project,
                List.of(productionLauncherClasses, generateLaunchSpec)));
    }

    private static String intellijModuleName(Project project, String sourceSetName) {
        StringBuilder moduleName = new StringBuilder();
        moduleName.append(project.getRootProject().getName().replace(" ", "_"));
        if (project != project.getRootProject()) {
            moduleName.append(project.getPath().replace(":", "."));
        }
        moduleName.append(".");
        moduleName.append(sourceSetName);
        return moduleName.toString();
    }

    private static String ideaConfigurationName(Project project, String runName) {
        if (project == project.getRootProject()) {
            return "Production " + runName;
        }
        return "Production " + project.getPath() + " " + runName;
    }

    private static List<File> launcherClasspath() {
        Set<File> files = new LinkedHashSet<>();
        addCodeSource(files, ProductionLauncherMain.class);
        ClassLoader classLoader = ProductionLauncherMain.class.getClassLoader();
        if (classLoader instanceof URLClassLoader urlClassLoader) {
            for (URL url : urlClassLoader.getURLs()) {
                try {
                    files.add(new File(url.toURI()));
                } catch (URISyntaxException exception) {
                    files.add(new File(url.getPath()));
                }
            }
        }
        return List.copyOf(files);
    }

    private static void addCodeSource(Set<File> files, Class<?> type) {
        if (type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) {
            return;
        }
        try {
            files.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException exception) {
            files.add(new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()));
        }
    }

    private static String classpath(List<File> files) {
        return files.stream()
                .map(File::getAbsolutePath)
                .sorted()
                .reduce((left, right) -> left + File.pathSeparator + right)
                .orElse("");
    }

    private static String quoteIfNeeded(String value) {
        if (value.chars().noneMatch(Character::isWhitespace)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String validateAndFormatRunName(ProductionRunSpec run, Map<String, String> runNamesByTaskSuffix) {
        String suffix = formatRunName(run.getName());
        if (suffix.isEmpty()) {
            throw new InvalidUserDataException(
                    "Production run '" + run.getName() + "' cannot be converted to a task name suffix.");
        }
        if (RESERVED_RUN_TASK_SUFFIXES.contains(suffix)) {
            throw new InvalidUserDataException(
                    "Production run '" + run.getName() + "' maps to reserved task name suffix '" + suffix + "'.");
        }

        String existingRunName = runNamesByTaskSuffix.putIfAbsent(suffix, run.getName());
        if (existingRunName != null && !existingRunName.equals(run.getName())) {
            throw new InvalidUserDataException("Production runs '" + existingRunName + "' and '" + run.getName()
                    + "' both map to task name suffix '" + suffix + "'.");
        }
        return suffix;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String formatRunName(String name) {
        StringBuilder formatted = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!Character.isLetterOrDigit(character)) {
                capitalizeNext = true;
                continue;
            }
            if (formatted.length() == 0 || capitalizeNext) {
                formatted.append(Character.toUpperCase(character));
            } else {
                formatted.append(character);
            }
            capitalizeNext = false;
        }
        return formatted.toString();
    }

    private record IdeaRunRegistration(
            Project project,
            ProductionExtension extension,
            String runName,
            TaskProvider<GenerateLaunchSpecTask> generateLaunchSpec) {}

    private static final class GradleTasksBeforeRun extends BeforeRunTask {
        private final Project project;
        private final List<TaskProvider<? extends Task>> tasks;

        private GradleTasksBeforeRun(String name, Project project, List<TaskProvider<? extends Task>> tasks) {
            this.type = "gradleTask";
            this.name = name;
            this.project = project;
            this.tasks = List.copyOf(tasks);
        }

        @Override
        public Map<String, ?> toMap() {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) super.toMap();
            result.put("projectPath", project.getProjectDir().getAbsolutePath().replace("\\", "/"));
            result.put("taskName", tasks.stream()
                    .map(task -> task.get().getPath())
                    .collect(Collectors.joining(" ")));
            return result;
        }
    }
}
