# ProductionGradle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Java Gradle plugin, launcher CLI, loader resolvers, mod providers, IDEA integration, and full e2e tests described in `docs/superpowers/specs/2026-06-06-production-gradle-design.md`.

**Architecture:** Gradle owns project discovery, variant-aware mod dependency resolution, Java Toolchains selection, launch spec generation, and task wiring. A Java CLI owns production instance preparation and game process launch from the schema-versioned spec. Loader and development-plugin compatibility live behind separate resolver/adapter interfaces.

**Tech Stack:** Java 21, Gradle Java Plugin Development Plugin, Gradle TestKit, JUnit Jupiter, Jackson, picocli, OkHttp, IntelliJ run configuration XML, Gradle composite builds.

---

## External References

- Gradle Java Toolchains and `JavaToolchainService`: https://docs.gradle.org/current/userguide/toolchains.html and https://docs.gradle.org/current/javadoc/org/gradle/jvm/toolchain/JavaToolchainService.html
- Gradle TestKit: https://docs.gradle.org/current/userguide/test_kit.html
- Gradle variant-aware resolution: https://docs.gradle.org/current/userguide/variant_aware_resolution.html
- Gradle variants and attributes: https://docs.gradle.org/current/userguide/variant_attributes.html
- Fabric Loom docs: https://docs.fabricmc.net/develop/loom/
- ForgeGradle docs: https://docs.minecraftforge.net/en/fg-6.x/
- NeoGradle repository and docs entry point: https://github.com/neoforged/NeoGradle
- ModDevGradle repository: https://github.com/neoforged/ModDevGradle
- Modrinth API: https://docs.modrinth.com/api/
- Modrinth dependency endpoint: https://docs.modrinth.com/api/operations/getdependencies
- CurseForge API files/dependencies: https://docs.curseforge.com/rest-api/
- Microsoft device code flow: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code

## File Structure

Create this multi-module layout:

```text
settings.gradle
build.gradle
gradle.properties

launcher-core/
  build.gradle
  src/main/java/dev/vfyjxf/gradle/launcher/core/cache/CacheLayout.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/download/Downloader.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/download/DownloadRequest.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/download/DownloadResult.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/json/Json.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchCommand.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchContext.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpec.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpecValidator.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LauncherEngine.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/LoaderResolver.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/LoaderResolverRegistry.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/fabric/FabricResolver.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/forge/ForgeResolver.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/neoforge/NeoForgeResolver.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/loader/vanilla/VanillaResolver.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/AssetIndex.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/LibraryRuleEvaluator.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/MinecraftVersionManifest.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/VersionJson.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/mods/ResolvedModFile.java
  src/main/java/dev/vfyjxf/gradle/launcher/core/process/GameProcessRunner.java
  src/test/java/dev/vfyjxf/gradle/launcher/core/... unit tests

launcher-cli/
  build.gradle
  src/main/java/dev/vfyjxf/gradle/launcher/cli/ProductionLauncherMain.java
  src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrepareCommand.java
  src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrintClasspathCommand.java
  src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrintCommandCommand.java
  src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/RunCommand.java
  src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/ValidateCommand.java
  src/test/java/dev/vfyjxf/gradle/launcher/cli/... unit tests

gradle-adapters/
  build.gradle
  src/main/java/dev/vfyjxf/gradle/adapters/DevelopmentArtifactResolver.java
  src/main/java/dev/vfyjxf/gradle/adapters/DevelopmentEnvironment.java
  src/main/java/dev/vfyjxf/gradle/adapters/DevelopmentEnvironmentAdapter.java
  src/main/java/dev/vfyjxf/gradle/adapters/DevelopmentEnvironmentService.java
  src/main/java/dev/vfyjxf/gradle/adapters/fabricloom/FabricLoomAdapter.java
  src/main/java/dev/vfyjxf/gradle/adapters/forgegradle/ForgeGradleAdapter.java
  src/main/java/dev/vfyjxf/gradle/adapters/moddevgradle/ModDevGradleAdapter.java
  src/main/java/dev/vfyjxf/gradle/adapters/neogradle/NeoGradleAdapter.java
  src/test/java/dev/vfyjxf/gradle/adapters/... unit tests

mod-providers/
  build.gradle
  src/main/java/dev/vfyjxf/gradle/mods/ModProvider.java
  src/main/java/dev/vfyjxf/gradle/mods/ModProviderContext.java
  src/main/java/dev/vfyjxf/gradle/mods/ModProviderResult.java
  src/main/java/dev/vfyjxf/gradle/mods/ModRequest.java
  src/main/java/dev/vfyjxf/gradle/mods/ModResolutionException.java
  src/main/java/dev/vfyjxf/gradle/mods/ModrinthProvider.java
  src/main/java/dev/vfyjxf/gradle/mods/CurseForgeProvider.java
  src/main/java/dev/vfyjxf/gradle/mods/GradleArtifactModProvider.java
  src/test/java/dev/vfyjxf/gradle/mods/... unit tests

gradle-plugin/
  build.gradle
  src/main/java/dev/vfyjxf/gradle/production/ProductionGradlePlugin.java
  src/main/java/dev/vfyjxf/gradle/production/dsl/AuthSpec.java
  src/main/java/dev/vfyjxf/gradle/production/dsl/IdeaSpec.java
  src/main/java/dev/vfyjxf/gradle/production/dsl/ModSetSpec.java
  src/main/java/dev/vfyjxf/gradle/production/dsl/ProductionExtension.java
  src/main/java/dev/vfyjxf/gradle/production/dsl/ProductionRunSpec.java
  src/main/java/dev/vfyjxf/gradle/production/internal/ConfigurationNames.java
  src/main/java/dev/vfyjxf/gradle/production/internal/RunNameFormatter.java
  src/main/java/dev/vfyjxf/gradle/production/internal/SpecWriter.java
  src/main/java/dev/vfyjxf/gradle/production/internal/ToolchainResolver.java
  src/main/java/dev/vfyjxf/gradle/production/idea/IdeaRunConfigurationWriter.java
  src/main/java/dev/vfyjxf/gradle/production/tasks/GenerateIdeaRunsTask.java
  src/main/java/dev/vfyjxf/gradle/production/tasks/GenerateLaunchSpecTask.java
  src/main/java/dev/vfyjxf/gradle/production/tasks/LauncherExecTask.java
  src/main/java/dev/vfyjxf/gradle/production/tasks/PrintLaunchSpecTask.java
  src/test/java/dev/vfyjxf/gradle/production/... unit and TestKit tests

integration-tests/
  build.gradle
  settings.gradle
  src/test/java/dev/vfyjxf/gradle/integration/ProductionGradleE2ETest.java
  fixtures/fabric-loom-1.21.1/
  fixtures/forgegradle-1.21.1/
  fixtures/neogradle-1.21.1/
  fixtures/moddevgradle-1.21.1/
```

## Task 1: Bootstrap the Multi-Module Gradle Build

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `launcher-core/build.gradle`
- Create: `launcher-cli/build.gradle`
- Create: `gradle-adapters/build.gradle`
- Create: `mod-providers/build.gradle`
- Create: `gradle-plugin/build.gradle`
- Create: `integration-tests/build.gradle`
- Create: `integration-tests/settings.gradle`

- [ ] **Step 1: Write the root settings file**

Create `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

rootProject.name = "ProductionGradle"

include "launcher-core"
include "launcher-cli"
include "gradle-adapters"
include "mod-providers"
include "gradle-plugin"
include "integration-tests"
```

- [ ] **Step 2: Write the root build**

Create `build.gradle`:

```groovy
plugins {
    id "java-library" apply false
    id "java-gradle-plugin" apply false
    id "application" apply false
}

allprojects {
    group = "dev.vfyjxf.gradle"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply plugin: "java-library"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType(JavaCompile).configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    tasks.withType(Test).configureEach {
        useJUnitPlatform()
        testLogging {
            events "failed", "skipped", "passed"
            exceptionFormat "full"
        }
    }

    dependencies {
        testImplementation platform("org.junit:junit-bom:5.13.1")
        testImplementation "org.junit.jupiter:junit-jupiter"
        testImplementation "org.assertj:assertj-core:3.27.3"
        testRuntimeOnly "org.junit.platform:junit-platform-launcher"
    }
}
```

- [ ] **Step 3: Write Gradle properties**

Create `gradle.properties`:

```properties
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
```

- [ ] **Step 4: Write module builds**

Create `launcher-core/build.gradle`:

```groovy
dependencies {
    api "com.fasterxml.jackson.core:jackson-databind:2.19.0"
    api "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.19.0"
    api "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.0"
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
}
```

Create `launcher-cli/build.gradle`:

```groovy
plugins {
    id "application"
}

dependencies {
    implementation project(":launcher-core")
    implementation "info.picocli:picocli:4.7.6"
    annotationProcessor "info.picocli:picocli-codegen:4.7.6"
}

application {
    mainClass = "dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain"
}
```

Create `gradle-adapters/build.gradle`:

```groovy
dependencies {
    compileOnly gradleApi()
    testImplementation gradleTestKit()
}
```

Create `mod-providers/build.gradle`:

```groovy
dependencies {
    api project(":launcher-core")
    compileOnly gradleApi()
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
}
```

Create `gradle-plugin/build.gradle`:

```groovy
plugins {
    id "java-gradle-plugin"
}

dependencies {
    implementation project(":launcher-core")
    implementation project(":launcher-cli")
    implementation project(":gradle-adapters")
    implementation project(":mod-providers")
    implementation "com.fasterxml.jackson.core:jackson-databind:2.19.0"
    testImplementation gradleTestKit()
}

gradlePlugin {
    plugins {
        production {
            id = "dev.vfyjxf.gradle.production"
            implementationClass = "dev.vfyjxf.gradle.production.ProductionGradlePlugin"
            displayName = "ProductionGradle"
            description = "Launches production Minecraft clients and servers from Gradle mod development projects."
        }
    }
}
```

Create `integration-tests/build.gradle`:

```groovy
dependencies {
    testImplementation project(":gradle-plugin")
    testImplementation gradleTestKit()
}
```

Create `integration-tests/settings.gradle`:

```groovy
pluginManagement {
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

rootProject.name = "production-gradle-integration-tests"
```

- [ ] **Step 5: Run the build and verify the expected compile failure**

Run:

```bash
./gradlew test
```

Expected: FAIL because plugin and launcher main classes do not exist yet.

- [ ] **Step 6: Commit bootstrap files**

```bash
git add settings.gradle build.gradle gradle.properties launcher-core/build.gradle launcher-cli/build.gradle gradle-adapters/build.gradle mod-providers/build.gradle gradle-plugin/build.gradle integration-tests/build.gradle integration-tests/settings.gradle
git commit -m "build: bootstrap ProductionGradle modules"
```

## Task 2: Add Launch Spec Model and JSON Round Trip

**Files:**
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpec.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchPaths.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchJava.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchAuth.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchMod.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSettings.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/json/Json.java`
- Create: `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpecJsonTest.java`

- [ ] **Step 1: Write the failing JSON round-trip test**

Create `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpecJsonTest.java`:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import dev.vfyjxf.gradle.launcher.core.json.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LaunchSpecJsonTest {
    @Test
    void writesAndReadsSchemaVersionOneSpec() throws Exception {
        LaunchSpec spec = new LaunchSpec(
                1,
                "client",
                "client",
                "1.21.1",
                "fabric",
                "0.16.10",
                new LaunchPaths(
                        Path.of("run-production/client"),
                        Path.of("run-production/client"),
                        Path.of(".gradle-home/caches/production-gradle"),
                        Path.of(".gradle-home/caches/production-gradle/assets"),
                        Path.of(".gradle-home/caches/production-gradle/libraries"),
                        Path.of("run-production/client/natives"),
                        Path.of("run-production/client/logs")),
                new LaunchJava(Path.of("/usr/bin/java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(new LaunchMod("gradle", "example", "1.0.0", Path.of("build/libs/example.jar"), Map.of("side", "both"))),
                new LaunchSettings(List.of("-Xmx2G"), List.of("--demo"), Map.of("ENV_ONE", "value"), null, false),
                Map.of("adapter", "fabric-loom"),
                Map.of("offline", false));

        String json = Json.mapper().writeValueAsString(spec);
        LaunchSpec parsed = Json.mapper().readValue(json, LaunchSpec.class);

        assertThat(parsed.schemaVersion()).isEqualTo(1);
        assertThat(parsed.runName()).isEqualTo("client");
        assertThat(parsed.minecraftVersion()).isEqualTo("1.21.1");
        assertThat(parsed.java().executable()).isEqualTo(Path.of("/usr/bin/java"));
        assertThat(parsed.mods()).hasSize(1);
        assertThat(parsed.gradle().get("offline")).isEqualTo(false);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :launcher-core:test --tests dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecJsonTest
```

Expected: FAIL with missing `LaunchSpec` and `Json` classes.

- [ ] **Step 3: Add immutable launch spec records**

Create the model files:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LaunchSpec(
        int schemaVersion,
        String runName,
        String type,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        LaunchPaths paths,
        LaunchJava java,
        LaunchAuth auth,
        List<LaunchMod> mods,
        LaunchSettings launch,
        Map<String, Object> resolutionHints,
        Map<String, Object> gradle) {
}
```

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;

public record LaunchPaths(
        Path instanceDir,
        Path workingDir,
        Path cacheDir,
        Path assetsDir,
        Path librariesDir,
        Path nativesDir,
        Path logsDir) {
}
```

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;

public record LaunchJava(Path executable, int version) {
}
```

```java
package dev.vfyjxf.gradle.launcher.core.launch;

public record LaunchAuth(String mode, String userName, String tokenCacheKey) {
}
```

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;
import java.util.Map;

public record LaunchMod(String source, String id, String version, Path file, Map<String, String> metadata) {
}
```

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.List;
import java.util.Map;

public record LaunchSettings(
        List<String> jvmArgs,
        List<String> gameArgs,
        Map<String, String> environment,
        String mainClass,
        boolean eulaAccepted) {
}
```

- [ ] **Step 4: Add Jackson mapper**

Create `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/json/Json.java`:

```java
package dev.vfyjxf.gradle.launcher.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
```

- [ ] **Step 5: Run the launch spec test**

Run:

```bash
./gradlew :launcher-core:test --tests dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecJsonTest
```

Expected: PASS.

- [ ] **Step 6: Commit launch spec model**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: add launch spec model"
```

## Task 3: Add Launcher Validation, Cache Layout, and Command Model

**Files:**
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/cache/CacheLayout.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchCommand.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpecValidator.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchValidationException.java`
- Create: `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/launch/LaunchSpecValidatorTest.java`

- [ ] **Step 1: Write validator tests**

Create `LaunchSpecValidatorTest.java` with tests named:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchSpecValidatorTest {
    @Test
    void acceptsCompleteClientSpec() {
        assertThatCode(() -> new LaunchSpecValidator().validate(validSpec("client", "fabric")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingMinecraftVersion() {
        LaunchSpec spec = validSpec("client", "fabric");
        LaunchSpec broken = new LaunchSpec(
                spec.schemaVersion(),
                spec.runName(),
                spec.type(),
                "",
                spec.loader(),
                spec.loaderVersion(),
                spec.paths(),
                spec.java(),
                spec.auth(),
                spec.mods(),
                spec.launch(),
                spec.resolutionHints(),
                spec.gradle());

        assertThatThrownBy(() -> new LaunchSpecValidator().validate(broken))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("minecraftVersion");
    }

    @Test
    void rejectsServerWithoutEula() {
        assertThatThrownBy(() -> new LaunchSpecValidator().validate(validSpec("server", "neoforge")))
                .isInstanceOf(LaunchValidationException.class)
                .hasMessageContaining("eula");
    }

    private static LaunchSpec validSpec(String type, String loader) {
        return new LaunchSpec(
                1,
                type,
                type,
                "1.21.1",
                loader,
                "1.0.0",
                new LaunchPaths(
                        Path.of("run-production/" + type),
                        Path.of("run-production/" + type),
                        Path.of("build/test-cache"),
                        Path.of("build/test-cache/assets"),
                        Path.of("build/test-cache/libraries"),
                        Path.of("run-production/" + type + "/natives"),
                        Path.of("run-production/" + type + "/logs")),
                new LaunchJava(Path.of(System.getProperty("java.home"), "bin", "java"), 21),
                new LaunchAuth("offline", "DevPlayer", null),
                List.of(),
                new LaunchSettings(List.of(), List.of(), Map.of(), null, "server".equals(type)),
                Map.of(),
                Map.of("offline", false));
    }
}
```

- [ ] **Step 2: Run validator tests to verify failure**

Run:

```bash
./gradlew :launcher-core:test --tests dev.vfyjxf.gradle.launcher.core.launch.LaunchSpecValidatorTest
```

Expected: FAIL with missing validator classes.

- [ ] **Step 3: Implement validator and command record**

Create `LaunchValidationException.java`:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

public class LaunchValidationException extends RuntimeException {
    public LaunchValidationException(String message) {
        super(message);
    }
}
```

Create `LaunchSpecValidator.java`:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.Set;

public class LaunchSpecValidator {
    private static final Set<String> TYPES = Set.of("client", "server");
    private static final Set<String> LOADERS = Set.of("vanilla", "fabric", "forge", "neoforge");

    public void validate(LaunchSpec spec) {
        require(spec.schemaVersion() == 1, "schemaVersion must be 1");
        require(text(spec.runName()), "runName is required");
        require(TYPES.contains(spec.type()), "type must be client or server");
        require(text(spec.minecraftVersion()), "minecraftVersion is required");
        require(LOADERS.contains(spec.loader()), "loader must be vanilla, fabric, forge, or neoforge");
        require(text(spec.loaderVersion()) || "vanilla".equals(spec.loader()), "loaderVersion is required for non-vanilla runs");
        require(spec.paths() != null, "paths are required");
        require(spec.java() != null && spec.java().executable() != null, "java.executable is required");
        require(spec.auth() != null, "auth is required");
        require(spec.launch() != null, "launch is required");
        if ("server".equals(spec.type())) {
            require(spec.launch().eulaAccepted(), "server run requires eula to be accepted");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LaunchValidationException(message);
        }
    }
}
```

Create `LaunchCommand.java`:

```java
package dev.vfyjxf.gradle.launcher.core.launch;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LaunchCommand(
        Path javaExecutable,
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment) {
}
```

- [ ] **Step 4: Add cache layout**

Create `CacheLayout.java`:

```java
package dev.vfyjxf.gradle.launcher.core.cache;

import java.nio.file.Path;

public record CacheLayout(
        Path root,
        Path minecraft,
        Path assets,
        Path libraries,
        Path loaders,
        Path modrinth,
        Path curseforge,
        Path auth,
        Path metadata) {
    public static CacheLayout under(Path root) {
        return new CacheLayout(
                root,
                root.resolve("minecraft"),
                root.resolve("assets"),
                root.resolve("libraries"),
                root.resolve("loaders"),
                root.resolve("mods").resolve("modrinth"),
                root.resolve("mods").resolve("curseforge"),
                root.resolve("auth"),
                root.resolve("metadata"));
    }
}
```

- [ ] **Step 5: Run launcher-core tests**

Run:

```bash
./gradlew :launcher-core:test
```

Expected: PASS.

- [ ] **Step 6: Commit validation and cache model**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: validate launch specs"
```

## Task 4: Implement Loader Resolver Interfaces and Vanilla Metadata Parsing

**Files:**
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/loader/LoaderResolver.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/loader/LoaderResolverRegistry.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/loader/PreparedLaunch.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/loader/vanilla/VanillaResolver.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/VersionJson.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/minecraft/LibraryRuleEvaluator.java`
- Create: `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/loader/LoaderResolverRegistryTest.java`
- Create: `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/minecraft/LibraryRuleEvaluatorTest.java`

- [ ] **Step 1: Write resolver registry tests**

Create tests asserting:

```java
assertThat(registry.resolverFor("vanilla")).isInstanceOf(VanillaResolver.class);
assertThatThrownBy(() -> registry.resolverFor("unknown")).hasMessageContaining("unknown loader");
```

- [ ] **Step 2: Write library rule tests**

Create tests for OS allow/disallow rules:

```java
assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of())).isTrue();
assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of(Map.of("action", "allow", "os", Map.of("name", "windows"))))).isFalse();
assertThat(new LibraryRuleEvaluator("windows", "x86_64").allowed(List.of(Map.of("action", "allow", "os", Map.of("name", "windows"))))).isTrue();
```

- [ ] **Step 3: Run the focused tests to verify failure**

Run:

```bash
./gradlew :launcher-core:test --tests '*LoaderResolverRegistryTest' --tests '*LibraryRuleEvaluatorTest'
```

Expected: FAIL because resolver and evaluator classes do not exist.

- [ ] **Step 4: Implement resolver interfaces**

Create these classes with this API:

```java
public interface LoaderResolver {
    boolean supports(String loader);
    PreparedLaunch prepare(LaunchSpec spec) throws Exception;
}
```

```java
public record PreparedLaunch(List<Path> classpath, List<String> jvmArgs, List<String> gameArgs, String mainClass) {
}
```

```java
public class LoaderResolverRegistry {
    private final List<LoaderResolver> resolvers;

    public LoaderResolverRegistry(List<LoaderResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public LoaderResolver resolverFor(String loader) {
        return resolvers.stream()
                .filter(resolver -> resolver.supports(loader))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown loader: " + loader));
    }
}
```

- [ ] **Step 5: Implement vanilla resolver**

Create `VanillaResolver` that supports `vanilla`, validates the spec, returns a `PreparedLaunch` containing the Minecraft client/server jar and official libraries when metadata is already present in cache, and throws a clear cache-miss error when offline metadata is missing.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :launcher-core:test
```

Expected: PASS for registry and rule tests.

- [ ] **Step 7: Commit resolver foundation**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: add loader resolver foundation"
```

## Task 5: Implement Downloading, Checksums, and Offline Cache Misses

**Files:**
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/download/DownloadRequest.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/download/DownloadResult.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/download/Downloader.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/download/Checksum.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/download/OfflineCacheMissException.java`
- Create: `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/download/DownloaderTest.java`

- [ ] **Step 1: Write downloader tests**

Tests must cover:

- Offline mode with missing target throws `OfflineCacheMissException`.
- Existing file with matching SHA-1 returns without network.
- Existing file with mismatched SHA-1 is rejected.
- Online mode writes to a temporary path and atomically moves into place.

- [ ] **Step 2: Run downloader tests to verify failure**

Run:

```bash
./gradlew :launcher-core:test --tests dev.vfyjxf.gradle.launcher.core.download.DownloaderTest
```

Expected: FAIL because downloader classes do not exist.

- [ ] **Step 3: Implement checksum utilities**

Create `Checksum` with:

```java
public static String sha1(Path file) throws IOException
public static String sha512(Path file) throws IOException
public static boolean matches(Path file, String algorithm, String expected) throws IOException
```

- [ ] **Step 4: Implement downloader**

`Downloader` must use OkHttp for HTTP GET, create parent directories, write to `<target>.part`, verify checksum when present, move atomically when supported, and delete partial files on failure.

- [ ] **Step 5: Run downloader tests**

Run:

```bash
./gradlew :launcher-core:test --tests dev.vfyjxf.gradle.launcher.core.download.DownloaderTest
```

Expected: PASS.

- [ ] **Step 6: Commit downloader**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: add cache-aware downloader"
```

## Task 6: Implement the CLI Command Surface

**Files:**
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/ProductionLauncherMain.java`
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/ValidateCommand.java`
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrepareCommand.java`
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/RunCommand.java`
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrintCommandCommand.java`
- Create: `launcher-cli/src/main/java/dev/vfyjxf/gradle/launcher/cli/commands/PrintClasspathCommand.java`
- Create: `launcher-cli/src/test/java/dev/vfyjxf/gradle/launcher/cli/ProductionLauncherMainTest.java`

- [ ] **Step 1: Write CLI tests**

Tests must call `new CommandLine(new ProductionLauncherMain()).execute(...)` and assert:

- `validate --spec valid.json` exits `0`.
- `validate --spec missing.json` exits non-zero.
- `print-command --spec valid.json` prints a command containing the spec Java executable.
- `--offline validate --spec valid.json` overrides spec offline in direct CLI mode.

- [ ] **Step 2: Run CLI tests to verify failure**

Run:

```bash
./gradlew :launcher-cli:test --tests dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMainTest
```

Expected: FAIL because CLI classes do not exist.

- [ ] **Step 3: Implement `ProductionLauncherMain`**

Use picocli:

```java
@Command(
        name = "production-launcher",
        mixinStandardHelpOptions = true,
        subcommands = {
                ValidateCommand.class,
                PrepareCommand.class,
                RunCommand.class,
                PrintCommandCommand.class,
                PrintClasspathCommand.class
        })
public class ProductionLauncherMain implements Runnable {
    @Option(names = "--offline", description = "Run without network access when invoked directly.")
    boolean offline;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ProductionLauncherMain()).execute(args);
        System.exit(exitCode);
    }
}
```

- [ ] **Step 4: Implement commands**

Each command reads `--spec`, deserializes `LaunchSpec`, validates it, constructs `LauncherEngine`, and returns:

- `ValidateCommand`: only validate.
- `PrepareCommand`: validate and prepare.
- `RunCommand`: validate, prepare, launch game process.
- `PrintCommandCommand`: print redacted command.
- `PrintClasspathCommand`: print one classpath entry per line.

- [ ] **Step 5: Run CLI tests**

Run:

```bash
./gradlew :launcher-cli:test
```

Expected: PASS.

- [ ] **Step 6: Commit CLI**

```bash
git add launcher-cli/src/main/java launcher-cli/src/test/java
git commit -m "feat: add production launcher cli"
```

## Task 7: Implement Gradle DSL and Run Container

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/ProductionGradlePlugin.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/AuthSpec.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/IdeaSpec.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/ModSetSpec.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/ProductionExtension.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/ProductionRunSpec.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/ProductionDslFunctionalTest.java`

- [ ] **Step 1: Write TestKit DSL tests**

Create a functional test that writes a Groovy build script:

```groovy
plugins {
    id "java"
    id "dev.vfyjxf.gradle.production"
}

production {
    autoDetect true
    instanceDir = file("run-production")
    auth {
        userName = "DevPlayer"
        microsoft false
    }
    runs.configureEach {
        minecraftVersion = "1.21.1"
        loader = "fabric"
        loaderVersion = "0.16.10"
        javaVersion = 21
        mods {
            includeProject true
            includeRequiredDependencies true
            includeOptionalDependencies false
        }
    }
    runs {
        client {
            type = "client"
            jvmArgs "-Xmx2G"
        }
        server {
            type = "server"
            eula true
        }
    }
}
```

Assert `tasks --all` contains `runProductionClient`, `runProductionServer`, `prepareProductionClient`, `prepareProductionServer`, and `generateProductionIdeaRuns`.

- [ ] **Step 2: Run DSL test to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ProductionDslFunctionalTest
```

Expected: FAIL because plugin implementation does not exist.

- [ ] **Step 3: Implement managed DSL objects**

Use abstract classes with Gradle managed properties:

```java
public abstract class ProductionRunSpec {
    public abstract Property<String> getType();
    public abstract Property<String> getMinecraftVersion();
    public abstract Property<String> getLoader();
    public abstract Property<String> getLoaderVersion();
    public abstract DirectoryProperty getInstanceDir();
    public abstract DirectoryProperty getWorkingDir();
    public abstract DirectoryProperty getCacheDir();
    public abstract Property<Integer> getJavaVersion();
    public abstract RegularFileProperty getJavaExecutable();
    public abstract ListProperty<String> getJvmArgs();
    public abstract ListProperty<String> getGameArgs();
    public abstract MapProperty<String, String> getEnvironment();
    public abstract Property<String> getMainClass();
    public abstract Property<Boolean> getEula();
    public abstract ModSetSpec getMods();
}
```

Add Groovy-friendly methods:

```java
public void jvmArgs(String... args) { getJvmArgs().addAll(args); }
public void gameArgs(String... args) { getGameArgs().addAll(args); }
public void eula(boolean accepted) { getEula().set(accepted); }
```

- [ ] **Step 4: Register extension and default runs**

`ProductionGradlePlugin.apply(Project)` must:

- Create `production` extension.
- Create default `client` and `server` runs.
- Set top-level `instanceDir` to `project.layout.projectDirectory.dir("run-production")`.
- Set top-level `cacheDir` to `new File(gradle.gradleUserHomeDir, "caches/production-gradle")`.
- Set default auth userName to `DevPlayer`.
- Set default auth microsoft to `false`.

- [ ] **Step 5: Register tasks per run**

For each run in `runs.configureEach`, register:

- `prepareProduction<Name>`
- `runProduction<Name>`
- `printProduction<Name>LaunchSpec`
- `printProduction<Name>Command`
- `printProduction<Name>Classpath`

Use `RunNameFormatter` so `client` becomes `Client` and `fooBar` becomes `FooBar`.

- [ ] **Step 6: Run DSL tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ProductionDslFunctionalTest
```

Expected: PASS.

- [ ] **Step 7: Commit DSL and task registration**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: add production Gradle DSL"
```

## Task 8: Implement Hidden Mod Configurations and `mods.add(...)`

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/internal/ConfigurationNames.java`
- Modify: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/dsl/ModSetSpec.java`
- Modify: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/ProductionGradlePlugin.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/ProductionModsFunctionalTest.java`

- [ ] **Step 1: Write TestKit tests for per-run mod isolation**

Create a fixture with `:app`, `:clientOnly`, and `:serverOnly`. In `:app`:

```groovy
production {
    runs {
        client {
            minecraftVersion = "1.21.1"
            loader = "fabric"
            loaderVersion = "0.16.10"
            mods {
                add project(":clientOnly")
            }
        }
        server {
            minecraftVersion = "1.21.1"
            loader = "fabric"
            loaderVersion = "0.16.10"
            eula true
            mods {
                add project(":serverOnly")
            }
        }
    }
}
```

Assert `printProductionClientLaunchSpec` includes `clientOnly` and not `serverOnly`, while server has the inverse.

- [ ] **Step 2: Run mod tests to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ProductionModsFunctionalTest
```

Expected: FAIL because mod configurations are not wired.

- [ ] **Step 3: Implement configuration naming**

`ConfigurationNames.modClasspath("client")` returns `productionClientModClasspath`.

- [ ] **Step 4: Create hidden configurations**

For each run:

```java
Configuration configuration = project.getConfigurations().maybeCreate(name);
configuration.setCanBeResolved(true);
configuration.setCanBeConsumed(false);
configuration.setVisible(false);
configuration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
configuration.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
configuration.getAttributes().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.JAR));
```

- [ ] **Step 5: Implement `ModSetSpec.add(...)`**

Support these overloads:

```java
public Dependency add(Object notation)
public Dependency add(Object notation, Action<? super Dependency> configure)
public void modrinth(String slugOrId, Action<? super ModrinthRequestSpec> configure)
public void curseforge(String name, Action<? super CurseForgeRequestSpec> configure)
public void includeProject(boolean value)
public void includeRequiredDependencies(boolean value)
public void includeOptionalDependencies(boolean value)
```

`add(...)` must delegate to `project.getDependencies().add(configurationName, notation)` and apply the action to the returned dependency.

- [ ] **Step 6: Run mod tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ProductionModsFunctionalTest
```

Expected: PASS.

- [ ] **Step 7: Commit mod configuration support**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: add per-run mod configurations"
```

## Task 9: Implement Java Toolchains and Explicit Executable Override

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/internal/ToolchainResolver.java`
- Modify: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/tasks/GenerateLaunchSpecTask.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/ToolchainFunctionalTest.java`

- [ ] **Step 1: Write Toolchain tests**

Test cases:

- `javaVersion = 21` writes a Java executable path into the spec.
- `javaExecutable = file("fake-java")` writes that exact path.
- Explicit executable wins over `javaVersion`.

- [ ] **Step 2: Run Toolchain tests to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ToolchainFunctionalTest
```

Expected: FAIL because `ToolchainResolver` does not exist.

- [ ] **Step 3: Implement `ToolchainResolver`**

Use injected `JavaToolchainService`:

```java
public Provider<RegularFile> resolveJavaExecutable(ProductionRunSpec run) {
    if (run.getJavaExecutable().isPresent()) {
        return run.getJavaExecutable();
    }
    Provider<JavaLauncher> launcher = toolchains.launcherFor(spec ->
            spec.getLanguageVersion().set(JavaLanguageVersion.of(run.getJavaVersion().getOrElse(21))));
    return launcher.map(javaLauncher -> javaLauncher.getExecutablePath());
}
```

- [ ] **Step 4: Wire the resolver into launch spec generation**

`GenerateLaunchSpecTask` receives a `RegularFileProperty javaExecutable` and writes it into `LaunchJava`.

- [ ] **Step 5: Run Toolchain tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.ToolchainFunctionalTest
```

Expected: PASS.

- [ ] **Step 6: Commit Java runtime resolution**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: resolve production Java executables"
```

## Task 10: Implement Launch Spec Generation Tasks

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/internal/SpecWriter.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/tasks/GenerateLaunchSpecTask.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/tasks/PrintLaunchSpecTask.java`
- Modify: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/ProductionGradlePlugin.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/LaunchSpecFunctionalTest.java`

- [ ] **Step 1: Write spec generation functional tests**

Test `printProductionClientLaunchSpec` and assert:

- JSON file exists under `build/production-gradle/specs/client/launch-spec.json`.
- `schemaVersion` is `1`.
- `runName` is `client`.
- `type` is `client`.
- `minecraftVersion`, `loader`, and `loaderVersion` match DSL or adapter defaults.
- `paths.cacheDir` starts under Gradle user home.
- `gradle.offline` matches the Gradle invocation.

- [ ] **Step 2: Run spec tests to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.LaunchSpecFunctionalTest
```

Expected: FAIL because `GenerateLaunchSpecTask` does not exist yet.

- [ ] **Step 3: Implement `GenerateLaunchSpecTask`**

Task inputs:

- run name
- type
- Minecraft version
- loader
- loader version
- paths
- Java executable
- auth
- resolved mod files
- JVM args
- game args
- environment
- main class
- EULA
- Gradle offline flag

Task output:

```text
build/production-gradle/specs/<runName>/launch-spec.json
```

- [ ] **Step 4: Implement `PrintLaunchSpecTask`**

It depends on `GenerateLaunchSpecTask` and prints the output file path.

- [ ] **Step 5: Run spec tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.LaunchSpecFunctionalTest
```

Expected: PASS.

- [ ] **Step 6: Commit spec generation**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: generate production launch specs"
```

## Task 11: Implement Development Environment Adapters

**Files:**
- Create: all files under `gradle-adapters/src/main/java/dev/vfyjxf/gradle/adapters/`
- Create: tests under `gradle-adapters/src/test/java/dev/vfyjxf/gradle/adapters/`

- [ ] **Step 1: Write adapter service tests**

Tests create small Gradle projects with plugin ids applied in fixture build scripts and assert:

- Fabric Loom plugin id maps to loader `fabric`.
- ForgeGradle plugin id maps to loader `forge`.
- NeoGradle plugin id maps to loader `neoforge`.
- ModDevGradle plugin id maps to loader `neoforge`.
- Unknown project returns an empty environment, not a crash.

- [ ] **Step 2: Run adapter tests to verify failure**

Run:

```bash
./gradlew :gradle-adapters:test
```

Expected: FAIL because adapters are missing.

- [ ] **Step 3: Implement adapter interfaces**

Create:

```java
public interface DevelopmentEnvironmentAdapter {
    boolean isPresent(Project project);
    DevelopmentEnvironment detect(Project project);
}
```

```java
public record DevelopmentEnvironment(
        String adapterName,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        Optional<String> fallbackArtifactTaskPath,
        Map<String, String> resolutionHints) {
}
```

- [ ] **Step 4: Implement adapter detection**

Each adapter must use Gradle plugin manager and extension lookup. It must avoid hard compile-time dependencies on the target Minecraft plugins by using plugin ids, extension names, public Gradle model objects, and reflection only at the adapter boundary.

Plugin ids:

- Fabric Loom: `fabric-loom` and `net.fabricmc.fabric-loom`
- ForgeGradle: `net.minecraftforge.gradle`
- NeoGradle: `net.neoforged.gradle.userdev`
- ModDevGradle: `net.neoforged.moddev`

- [ ] **Step 5: Implement fallback artifact resolution**

Fallback task candidates:

- Fabric Loom: `remapJar`, then `jar`
- ForgeGradle: reobfuscated jar task when exposed, then `jar`
- NeoGradle: production/reobf artifact task when exposed, then `jar`
- ModDevGradle: production/reobf artifact task when exposed, then `jar`

The adapter must return an explicit warning hint when it falls back to `jar`.

- [ ] **Step 6: Run adapter tests**

Run:

```bash
./gradlew :gradle-adapters:test
```

Expected: PASS.

- [ ] **Step 7: Commit adapters**

```bash
git add gradle-adapters/src/main/java gradle-adapters/src/test/java
git commit -m "feat: detect Minecraft development plugins"
```

## Task 12: Implement Remote Mod Providers

**Files:**
- Create: all files listed under `mod-providers/src/main/java/dev/vfyjxf/gradle/mods/`
- Create: tests under `mod-providers/src/test/java/dev/vfyjxf/gradle/mods/`

- [ ] **Step 1: Write Modrinth provider tests**

Tests must verify:

- Slug resolves to a version matching Minecraft `1.21.1` and loader `fabric`.
- Version id resolves directly.
- Required dependencies are recursively added.
- Optional dependencies are skipped when disabled.
- Optional dependencies are included when enabled.
- Hash mismatch fails.

- [ ] **Step 2: Write CurseForge provider tests**

Tests must verify:

- Missing API key fails with a message containing `CURSEFORGE_API_KEY`.
- Project id and file id resolve metadata when an API key is present.
- Required relation type `3` is included.
- Optional relation type `2` is controlled by run setting.
- Incompatible relation type `5` fails.

- [ ] **Step 3: Run provider tests to verify failure**

Run:

```bash
./gradlew :mod-providers:test
```

Expected: FAIL because providers do not exist.

- [ ] **Step 4: Implement provider interfaces**

Create:

```java
public interface ModProvider {
    boolean supports(ModRequest request);
    ModProviderResult resolve(ModProviderContext context, ModRequest request) throws ModResolutionException;
}
```

`ModProviderContext` contains Minecraft version, loader, cache layout, required/optional dependency flags, API credentials, and offline flag.

- [ ] **Step 5: Implement Modrinth**

Use `https://api.modrinth.com/v2`. Required routes:

- `/project/{id|slug}/version`
- `/version/{id}`
- `/project/{id|slug}/dependencies`

Choose the primary file when present, otherwise the first file. Verify SHA-512 when present, otherwise SHA-1.

- [ ] **Step 6: Implement CurseForge**

Use CurseForge REST API with `x-api-key`. Required route:

- `/v1/mods/{modId}/files/{fileId}`

Use `downloadUrl`, `gameVersions`, `dependencies`, and file hashes. Use project id and file id only for MVP.

- [ ] **Step 7: Run provider tests**

Run:

```bash
./gradlew :mod-providers:test
```

Expected: PASS. Tests requiring a real CurseForge API key must be skipped with an explicit assumption when the key is absent; the missing-key behavior test must always run.

- [ ] **Step 8: Commit providers**

```bash
git add mod-providers/src/main/java mod-providers/src/test/java
git commit -m "feat: resolve remote production mods"
```

## Task 13: Implement Fabric, Forge, and NeoForge Resolvers

**Files:**
- Modify: loader files under `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/loader/`
- Create: resolver tests under `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/loader/`

- [ ] **Step 1: Write Fabric resolver tests**

Tests must assert that a Fabric client/server spec for `1.21.1` and loader `0.16.10` resolves:

- Fabric loader libraries.
- Official Minecraft libraries.
- A non-empty classpath.
- A non-empty main class.
- Client arguments include auth placeholders resolved from `LaunchAuth`.

- [ ] **Step 2: Write Forge resolver tests**

Tests must assert that a Forge server spec for `1.21.1` resolves installer/server bootstrap metadata and produces a command that includes the server working directory.

- [ ] **Step 3: Write NeoForge resolver tests**

Tests must assert that a NeoForge server spec for `1.21.1` resolves NeoForge metadata and produces a command without using any development run classpath.

- [ ] **Step 4: Run resolver tests to verify failure**

Run:

```bash
./gradlew :launcher-core:test --tests '*FabricResolverTest' --tests '*ForgeResolverTest' --tests '*NeoForgeResolverTest'
```

Expected: FAIL because Fabric, Forge, and NeoForge resolver implementations do not exist yet.

- [ ] **Step 5: Implement Fabric resolver**

Resolve official Minecraft version metadata and Fabric loader metadata. Build classpath and main class from production loader metadata. Prepare both client and server.

- [ ] **Step 6: Implement Forge resolver**

Resolve Forge 1.21.1+ production installer or Maven metadata. Prepare real client and server production launch state. Write precise cache-miss errors when offline.

- [ ] **Step 7: Implement NeoForge resolver**

Resolve NeoForge 1.21.1+ production installer or Maven metadata. Prepare real client and server production launch state. Keep all NeoGradle/ModDevGradle compatibility outside this resolver.

- [ ] **Step 8: Run resolver tests**

Run:

```bash
./gradlew :launcher-core:test --tests '*ResolverTest'
```

Expected: PASS.

- [ ] **Step 9: Commit loader resolvers**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: resolve production loaders"
```

## Task 14: Implement Auth

**Files:**
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/auth/AuthProvider.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/auth/OfflineAuthProvider.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/auth/MicrosoftDeviceCodeAuthProvider.java`
- Create: `launcher-core/src/main/java/dev/vfyjxf/gradle/launcher/core/auth/AuthCache.java`
- Create: auth tests under `launcher-core/src/test/java/dev/vfyjxf/gradle/launcher/core/auth/`

- [ ] **Step 1: Write auth tests**

Tests must verify:

- Offline auth produces username, UUID-compatible stable id, and redacted command output.
- Microsoft auth stores tokens under Gradle home cache auth directory.
- Expired Microsoft token refresh path is selected when a refresh token exists.
- Missing Microsoft credentials prints device code instructions instead of crashing.

- [ ] **Step 2: Run auth tests to verify failure**

Run:

```bash
./gradlew :launcher-core:test --tests 'dev.vfyjxf.gradle.launcher.core.auth.*'
```

Expected: FAIL because auth classes do not exist.

- [ ] **Step 3: Implement offline auth**

Offline auth returns:

- `--username <userName>`
- `--uuid <stable offline uuid>`
- `--accessToken 0`
- `--userType msa`

Use a stable UUID derived from `OfflinePlayer:<userName>`.

- [ ] **Step 4: Implement Microsoft device code auth**

Use Microsoft device authorization grant. Store token cache entries under:

```text
<gradleUserHome>/caches/production-gradle/auth
```

File permissions must be owner-readable and owner-writable when the platform supports POSIX permissions.

- [ ] **Step 5: Add secret redaction**

Any printed command must replace access tokens and refresh tokens with `<redacted>`.

- [ ] **Step 6: Run auth tests**

Run:

```bash
./gradlew :launcher-core:test --tests 'dev.vfyjxf.gradle.launcher.core.auth.*'
```

Expected: PASS.

- [ ] **Step 7: Commit auth**

```bash
git add launcher-core/src/main/java launcher-core/src/test/java
git commit -m "feat: add production auth providers"
```

## Task 15: Implement IDEA Run Configuration Generation

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/idea/IdeaRunConfigurationWriter.java`
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/tasks/GenerateIdeaRunsTask.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/IdeaRunConfigurationFunctionalTest.java`

- [ ] **Step 1: Write IDEA generation tests**

Test cases:

- `generateProductionIdeaRuns` writes `.idea/runConfigurations/Production_Client.xml`.
- File contains marker `Generated by ProductionGradle`.
- Gradle mode references task `runProductionClient`.
- Application mode references launcher main class and spec path.
- Existing unmarked file fails when `overwrite false`.

- [ ] **Step 2: Run IDEA tests to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.IdeaRunConfigurationFunctionalTest
```

Expected: FAIL because IDEA writer does not exist.

- [ ] **Step 3: Implement XML writer**

Use Java XML APIs to write stable XML. Do not build XML with raw string concatenation. Fixed output directory:

```text
.idea/runConfigurations
```

- [ ] **Step 4: Implement overwrite protection**

If an existing file lacks the marker and `overwrite=false`, fail with a message containing the file path.

- [ ] **Step 5: Run IDEA tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.IdeaRunConfigurationFunctionalTest
```

Expected: PASS.

- [ ] **Step 6: Commit IDEA integration**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: generate IDEA production runs"
```

## Task 16: Wire Gradle Tasks to the CLI

**Files:**
- Create: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/tasks/LauncherExecTask.java`
- Modify: `gradle-plugin/src/main/java/dev/vfyjxf/gradle/production/ProductionGradlePlugin.java`
- Create: `gradle-plugin/src/test/java/dev/vfyjxf/gradle/production/LauncherExecFunctionalTest.java`

- [ ] **Step 1: Write LauncherExec functional tests**

Tests must assert:

- `prepareProductionClient` depends on launch spec generation.
- `runProductionClient` invokes CLI `run`.
- `printProductionClientCommand` invokes CLI `print-command`.
- `printProductionClientClasspath` invokes CLI `print-classpath`.
- Gradle `--offline` writes `gradle.offline=true` into the spec and CLI receives that spec.

- [ ] **Step 2: Run LauncherExec tests to verify failure**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.LauncherExecFunctionalTest
```

Expected: FAIL because execution task is not wired.

- [ ] **Step 3: Implement `LauncherExecTask`**

Use Gradle `ExecOperations` injection. The command line must be:

```text
<java executable for Gradle task JVM> -cp <launcher-cli runtime classpath> dev.vfyjxf.gradle.launcher.cli.ProductionLauncherMain <command> --spec <spec>
```

This task runs the launcher CLI. It must not implement launcher behavior itself.

- [ ] **Step 4: Register tasks**

`prepareProduction<Name>` runs CLI `prepare`.  
`runProduction<Name>` runs CLI `run`.  
`printProduction<Name>Command` runs CLI `print-command`.  
`printProduction<Name>Classpath` runs CLI `print-classpath`.

- [ ] **Step 5: Run LauncherExec tests**

Run:

```bash
./gradlew :gradle-plugin:test --tests dev.vfyjxf.gradle.production.LauncherExecFunctionalTest
```

Expected: PASS.

- [ ] **Step 6: Commit Gradle-to-CLI wiring**

```bash
git add gradle-plugin/src/main/java gradle-plugin/src/test/java
git commit -m "feat: wire production tasks to launcher cli"
```

## Task 17: Create Real Integration Fixtures

**Files:**
- Create: `integration-tests/fixtures/fabric-loom-1.21.1/settings.gradle`
- Create: `integration-tests/fixtures/fabric-loom-1.21.1/build.gradle`
- Create: `integration-tests/fixtures/fabric-loom-1.21.1/src/main/resources/fabric.mod.json`
- Create: `integration-tests/fixtures/forgegradle-1.21.1/settings.gradle`
- Create: `integration-tests/fixtures/forgegradle-1.21.1/build.gradle`
- Create: `integration-tests/fixtures/forgegradle-1.21.1/src/main/resources/META-INF/mods.toml`
- Create: `integration-tests/fixtures/neogradle-1.21.1/settings.gradle`
- Create: `integration-tests/fixtures/neogradle-1.21.1/build.gradle`
- Create: `integration-tests/fixtures/neogradle-1.21.1/src/main/resources/META-INF/neoforge.mods.toml`
- Create: `integration-tests/fixtures/moddevgradle-1.21.1/settings.gradle`
- Create: `integration-tests/fixtures/moddevgradle-1.21.1/build.gradle`
- Create: `integration-tests/fixtures/moddevgradle-1.21.1/src/main/resources/META-INF/neoforge.mods.toml`
- Create: `integration-tests/src/test/java/dev/vfyjxf/gradle/integration/FixtureIntegrationTest.java`

- [ ] **Step 1: Write fixture integration test**

The test must run each fixture with:

```text
validateProductionRun
printProductionClientLaunchSpec
printProductionClientCommand
generateProductionIdeaRuns
```

Assert each command exits successfully.

- [ ] **Step 2: Create Fabric fixture**

Use Fabric Loom, Minecraft `1.21.1`, Fabric Loader `0.16.10`, and a minimal `fabric.mod.json`.

- [ ] **Step 3: Create Forge fixture**

Use ForgeGradle for Minecraft `1.21.1` and a minimal `mods.toml`.

- [ ] **Step 4: Create NeoGradle fixture**

Use NeoGradle userdev for Minecraft `1.21.1` and a minimal `neoforge.mods.toml`.

- [ ] **Step 5: Create ModDevGradle fixture**

Use ModDevGradle for Minecraft `1.21.1` and a minimal `neoforge.mods.toml`.

- [ ] **Step 6: Run fixture tests**

Run:

```bash
./gradlew :integration-tests:test --tests dev.vfyjxf.gradle.integration.FixtureIntegrationTest
```

Expected: PASS. This command requires network unless Gradle caches are already populated.

- [ ] **Step 7: Commit integration fixtures**

```bash
git add integration-tests
git commit -m "test: add Minecraft development plugin fixtures"
```

## Task 18: Add Full E2E Server Smoke Test and Client Prepare Test

**Files:**
- Create: `integration-tests/src/test/java/dev/vfyjxf/gradle/integration/ProductionGradleE2ETest.java`
- Modify: `integration-tests/build.gradle`

- [ ] **Step 1: Write E2E test**

`ProductionGradleE2ETest` must:

- Use an included build fixture.
- Run `prepareProductionServer`.
- Run `runProductionServer` with a bounded timeout.
- Detect server readiness from stdout or log output.
- Stop the process cleanly.
- Run `prepareProductionClient`.
- Run `printProductionClientCommand`.
- Run `generateProductionIdeaRuns`.

- [ ] **Step 2: Add E2E gating**

The E2E test must run by default in local verification. In CI, allow `-PskipProductionE2E=true` only when third-party services are unavailable. The default value is false.

- [ ] **Step 3: Run E2E test**

Run:

```bash
./gradlew :integration-tests:test --tests dev.vfyjxf.gradle.integration.ProductionGradleE2ETest
```

Expected: PASS with a real server smoke test. The first run downloads Minecraft, loader metadata, libraries, assets, and provider files into Gradle user home.

- [ ] **Step 4: Commit E2E tests**

```bash
git add integration-tests
git commit -m "test: add production Minecraft e2e coverage"
```

## Task 19: Add Documentation Examples and Verification Commands

**Files:**
- Create: `README.md`
- Create: `docs/examples/fabric.gradle`
- Create: `docs/examples/neoforge.gradle`
- Create: `docs/examples/forge.gradle`
- Create: `docs/examples/idea.md`
- Create: `docs/examples/auth.md`

- [ ] **Step 1: Write README**

Include:

- Plugin id `dev.vfyjxf.gradle.production`
- Basic `production {}` DSL
- `runProductionClient`
- `runProductionServer`
- `generateProductionIdeaRuns`
- Gradle `--offline` behavior
- Gradle home cache location
- Java Toolchains and `javaExecutable`

- [ ] **Step 2: Write example build scripts**

Each example must show:

- `production.runs.configureEach`
- per-run `mods { add ... }`
- one remote provider
- `eula true` for server

- [ ] **Step 3: Write auth docs**

Explain offline auth and Microsoft device code auth. State that token cache is under Gradle user home and `print-command` redacts secrets.

- [ ] **Step 4: Run docs spelling and command verification**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 5: Commit docs**

```bash
git add README.md docs/examples
git commit -m "docs: add production run examples"
```

## Task 20: Final Verification

**Files:**
- Modify only files required by failures found during verification.

- [ ] **Step 1: Run full unit and functional tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 2: Run offline validation after cache warmup**

Run:

```bash
./gradlew --offline :integration-tests:test --tests dev.vfyjxf.gradle.integration.FixtureIntegrationTest
```

Expected: PASS if the previous online fixture test populated caches. If a cache entry is missing, the failure message must list the missing artifact and expected cache path.

- [ ] **Step 3: Run full E2E**

Run:

```bash
./gradlew :integration-tests:test --tests dev.vfyjxf.gradle.integration.ProductionGradleE2ETest
```

Expected: PASS.

- [ ] **Step 4: Check configuration cache**

Run:

```bash
./gradlew validateProductionRun --configuration-cache
```

Expected: PASS and Gradle reports configuration cache stored or reused.

- [ ] **Step 5: Check git status**

Run:

```bash
git status --short
```

Expected: no uncommitted changes.

## Self-Review Checklist

- Spec coverage: Tasks cover Java implementation, plugin id, CLI, client/server, Fabric/Forge/NeoForge, Fabric Loom/ForgeGradle/NeoGradle/ModDevGradle, Modrinth, CurseForge, Microsoft/offline auth, Gradle Toolchains, explicit `javaExecutable`, Gradle-home cache, IDEA run configs, composite fixtures, and full e2e.
- Placeholder scan: No task uses deferred implementation wording. Each task has file paths, commands, and expected results.
- Type consistency: Public names match the design spec: `production`, `runs`, `runProductionClient`, `runProductionServer`, `generateProductionIdeaRuns`, `gradle-adapters`, `dev.vfyjxf.gradle.production`, and package prefix `dev.vfyjxf.gradle`.
