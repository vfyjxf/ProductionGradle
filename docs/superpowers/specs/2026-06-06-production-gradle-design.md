# ProductionGradle Design

Date: 2026-06-06

## Goal

Build a Java-based Gradle plugin that can launch a real production Minecraft client or server from a mod development project.

The plugin must support Minecraft 1.21.1 and newer, with Fabric, Forge, and NeoForge. It must be compatible with projects using Fabric Loom, ForgeGradle, NeoGradle, and ModDevGradle. Development mods from the current project or other projects must be launchable in a production-style instance without reusing the development run classpath.

The package namespace is `dev.vfyjxf.gradle`. The Gradle plugin id is:

```text
dev.vfyjxf.gradle.production
```

The implementation language is Java.

## Non-negotiable Constraints

- Production launch must be real production launch behavior, not a wrapper around development runs.
- Client and server launch must both be implemented.
- Fabric, Forge, and NeoForge support for Minecraft 1.21.1+ is in the MVP scope.
- ModDevGradle, NeoGradle, ForgeGradle, and Fabric Loom compatibility is in the MVP scope.
- Modrinth and CurseForge mod sources are in the MVP scope.
- Microsoft login and offline account support are in the MVP scope.
- IDEA run configuration generation is in the MVP scope.
- Full end-to-end testing is required.
- Tests should use real integration paths as much as possible. Mocking is allowed only for narrow, hard-to-control network or service failure cases.
- JDK download, installation, and toolchain resolution must not be implemented in the launcher backend. Java selection must use Gradle's Java Toolchains API by default.
- The launcher CLI may use only the Java executable passed in the launch spec, the current JVM, or a user-provided Java executable when run directly. It must not download JDKs.

## Architecture

Use an independent launcher plus a thin Gradle orchestration layer.

Gradle resolves project state, development artifacts, toolchains, run configuration, mod dependencies, and provider inputs. It writes a schema-versioned launch spec for each production run. The CLI reads the spec, prepares a production instance, and starts the actual game process.

This keeps Gradle compatibility code out of the launcher, keeps loader-specific launch behavior out of the Gradle DSL, and allows the launcher to be tested independently.

## Modules

### `launcher-core`

Java library for production launch preparation and command construction.

Responsibilities:

- Parse Minecraft version metadata.
- Resolve official libraries, assets, natives, client jar, and server jar.
- Resolve Fabric, Forge, and NeoForge production loader metadata.
- Prepare production client and server launch state.
- Build JVM command lines using the Java executable provided in the launch spec.
- Copy resolved mods into the run instance.
- Validate cache availability when Gradle offline mode is active.

It must not depend on Gradle APIs and must not download or install JDKs.

### `launcher-cli`

Java command-line application around `launcher-core`.

Commands:

```text
production-launcher validate --spec <file>
production-launcher prepare --spec <file>
production-launcher run --spec <file>
production-launcher run-client --spec <file>
production-launcher run-server --spec <file>
production-launcher print-command --spec <file>
production-launcher print-classpath --spec <file>
```

The CLI is intentionally simple but complete. It reads a launch spec, validates it, prepares the instance, and starts the production process. `print-command` and `print-classpath` are required for diagnostics and tests.

### `gradle-plugin`

Java Gradle plugin implementation.

Responsibilities:

- Register plugin id `dev.vfyjxf.gradle.production`.
- Provide the `production {}` DSL.
- Create default `client` and `server` runs.
- Create per-run hidden resolvable configurations for mods.
- Use Gradle variant-aware dependency resolution for project and module dependencies.
- Use Gradle Java Toolchains API to choose the Java executable by default.
- Allow users to explicitly provide a Java executable per run.
- Generate per-run launch specs.
- Invoke `launcher-cli`.
- Generate IntelliJ IDEA run configurations in `.idea/runConfigurations`.

### `gradle-adapters`

Java module that isolates compatibility with existing Minecraft development Gradle plugins.

Adapters:

- Fabric Loom
- ForgeGradle
- NeoGradle
- ModDevGradle

Responsibilities:

- Detect Minecraft version, loader type, and loader version.
- Detect production artifact hints for the current project.
- Detect useful cache or metadata hints where available.
- Provide fallback task output only when Gradle variant-aware resolution cannot select a production artifact.

The main Gradle plugin depends on stable adapter interfaces only. It must not mix direct Loom, ForgeGradle, NeoGradle, or ModDevGradle compatibility logic into the main plugin code.

Initial public adapter types:

```text
DevelopmentEnvironmentAdapter
DevelopmentEnvironment
DevelopmentArtifactResolver
```

### `mod-providers`

Java module for remote mod source resolution.

Providers:

- Gradle resolved artifacts
- Local files and file collections
- Maven coordinates
- Modrinth
- CurseForge

Required dependencies are resolved automatically. Optional dependencies are included only when the run enables them. Loader or Minecraft version mismatch fails the run.

### `integration-tests`

Composite build test area with real fixture projects.

Required fixture types:

```text
integration-tests/fixtures/fabric-loom-1.21.1
integration-tests/fixtures/forgegradle-1.21.1
integration-tests/fixtures/neogradle-1.21.1
integration-tests/fixtures/moddevgradle-1.21.1
```

Fixtures consume the plugin through an included build.

## Gradle DSL

The user-facing configuration lives under `production {}`. The plugin must not require users to put production mod dependencies in the top-level `dependencies {}` block.

Top-level properties are global conventions. Every run can override the relevant values.

Groovy DSL target:

```groovy
plugins {
    id "dev.vfyjxf.gradle.production"
}

production {
    autoDetect true

    instanceDir = file("run-production")
    cacheDir = new File(gradle.gradleUserHomeDir, "caches/production-gradle")

    auth {
        userName = "DevPlayer"
        microsoft false
    }

    idea {
        enabled true
        mode = "gradle"
        overwrite false
    }

    runs.configureEach {
        minecraftVersion = "1.21.1"
        loader = "neoforge"
        loaderVersion = "21.1.0"

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
            instanceDir = file("run-production/client")
            workingDir = file("run-production/client")

            jvmArgs "-Xmx4G"
            gameArgs "--quickPlaySingleplayer", "TestWorld"

            mods {
                add project(":common")
                add files("mods/client-only.jar")
                add "maven.modrinth:sodium:mc1.21.1-..."

                modrinth("sodium") {
                    version = "mc1.21.1-..."
                }
            }
        }

        server {
            type = "server"
            instanceDir = file("run-production/server")
            workingDir = file("run-production/server")

            javaExecutable = file("/path/to/java")
            eula true

            jvmArgs "-Xmx4G"

            mods {
                curseforge("jei") {
                    projectId = 238222
                    fileId = 1234567
                }
            }
        }
    }
}
```

Kotlin DSL target:

```kotlin
plugins {
    id("dev.vfyjxf.gradle.production")
}

production {
    autoDetect(true)

    instanceDir = file("run-production")
    cacheDir = File(gradle.gradleUserHomeDir, "caches/production-gradle")

    auth {
        userName = "DevPlayer"
        microsoft(false)
    }

    idea {
        enabled(true)
        mode = "gradle"
        overwrite(false)
    }

    runs.configureEach {
        minecraftVersion = "1.21.1"
        loader = "neoforge"
        loaderVersion = "21.1.0"

        javaVersion = 21

        mods {
            includeProject(true)
            includeRequiredDependencies(true)
            includeOptionalDependencies(false)
        }
    }

    runs {
        named("client") {
            jvmArgs("-Xmx4G")
            gameArgs("--quickPlaySingleplayer", "TestWorld")

            mods {
                add(project(":common"))
                add(files("mods/client-only.jar"))
                add("maven.modrinth:sodium:mc1.21.1-...")
                modrinth("sodium") {
                    version = "mc1.21.1-..."
                }
            }
        }

        named("server") {
            javaExecutable = file("/path/to/java")
            eula(true)
            jvmArgs("-Xmx4G")
        }
    }
}
```

## Run Model

`production.runs` is a Gradle `NamedDomainObjectContainer`.

Default runs:

- `client`, type `client`
- `server`, type `server`

For run name `fooBar`, generated tasks are:

```text
prepareProductionFooBar
runProductionFooBar
printProductionFooBarLaunchSpec
printProductionFooBarCommand
printProductionFooBarClasspath
```

Default task aliases:

```text
prepareProductionClient
runProductionClient
prepareProductionServer
runProductionServer
validateProductionRun
generateProductionIdeaRuns
```

Each run can independently configure:

- `type`
- `minecraftVersion`
- `loader`
- `loaderVersion`
- `instanceDir`
- `workingDir`
- `cacheDir`
- `auth`
- `javaVersion`
- `javaExecutable`
- `jvmArgs`
- `gameArgs`
- `environment`
- `mainClass`
- `eula`
- `mods`

Top-level `instanceDir` defaults to:

```text
<projectDir>/run-production
```

The run-specific default instance directory is:

```text
<projectDir>/run-production/<runName>
```

Top-level `cacheDir` defaults to:

```text
<gradleUserHome>/caches/production-gradle
```

All reusable caches must default under Gradle user home.

## Offline Semantics

There is no `offline` production DSL option.

Offline behavior follows Gradle:

```text
gradle --offline runProductionClient
```

The Gradle plugin reads:

```text
gradle.startParameter.isOffline()
```

and writes that value to the launch spec.

When offline is true:

- Gradle dependency resolution follows Gradle's own offline behavior.
- CLI metadata and asset resolution must not make network requests.
- Missing cache entries fail with clear paths and artifact identifiers.

When users run the CLI directly, `--offline` is allowed as a CLI flag. This is not exposed as a Gradle DSL property.

## Java Runtime Selection

Default Java selection uses Gradle Java Toolchains.

Per run:

```groovy
production {
    runs {
        client {
            javaVersion = 21
        }
    }
}
```

The Gradle plugin uses `JavaToolchainService` to resolve a `JavaLauncher`, then writes the resolved executable path to the launch spec.

Users may override the executable:

```groovy
production {
    runs {
        client {
            javaExecutable = file("/path/to/java")
        }
    }
}
```

Priority:

1. Run `javaExecutable`
2. Run `javaVersion` through Gradle Java Toolchains
3. Project Java toolchain convention
4. Current Gradle JVM as fallback, with a warning when it does not satisfy Minecraft requirements

The launcher backend must never download or install JDKs.

## Mod Configuration

Every run has its own hidden Gradle configuration for mod classpath resolution. Users configure mods inside `production {}`.

For run `client`, the plugin creates an internal configuration such as:

```text
productionClientModClasspath
```

Configuration properties:

- `canBeResolved = true`
- `canBeConsumed = false`
- hidden from normal user-facing dependency declarations

The plugin sets Gradle-native attributes such as Java runtime usage, library category, jar library elements, and target JVM version. Loader and Minecraft attributes are used only when the participating development plugin or project already publishes them as Gradle attributes. The plugin must not invent a parallel variant selection language. Variant selection must be delegated to Gradle dependency resolution.

User-facing mod additions:

```groovy
production {
    runs {
        client {
            mods {
                add project(":mod")

                add(project(":api")) {
                    capabilities {
                        requireCapability "com.example:api-neoforge"
                    }
                }

                add project(path: ":mod", configuration: "runtimeElements")

                add("group:name:version") {
                    transitive = false
                    exclude group: "bad.group", module: "bad-module"
                }

                add files("mods/local.jar")
                add fileTree("mods") { include "*.jar" }
            }
        }
    }
}
```

The `add(...)` method delegates to Gradle dependency creation and configuration. It must not implement a custom variant selection language.

`includeProject true` is a convenience that adds the current project to the run's mod configuration. Gradle variant resolution is used first. If no usable production artifact is available, `gradle-adapters` may provide a fallback task output such as a remapped or reobfuscated jar.

## Remote Mod Providers

### Modrinth

Supported selectors:

- project slug
- project id
- version id
- explicit version string where resolvable

Provider behavior:

- Filter by run Minecraft version.
- Filter by run loader.
- Download selected jar to Gradle home cache.
- Resolve required dependencies recursively.
- Resolve optional dependencies only when enabled.
- Fail on incompatible loader or Minecraft version.
- Verify hashes when metadata provides them.

### CurseForge

Supported selectors:

- project id
- file id

Slug/name lookup is not part of the MVP. CurseForge support in the MVP uses project id and file id so the behavior is deterministic.

Provider behavior:

- API key is required for CurseForge-backed runs.
- API key can come from a Gradle property or environment variable.
- Required dependencies are resolved recursively.
- Optional dependencies are controlled per run.
- Loader and Minecraft mismatches fail.
- Download restrictions produce actionable errors.

Credential inputs:

```text
production.curseforgeApiKey
CURSEFORGE_API_KEY
```

## Cache Layout

Default cache root:

```text
<gradleUserHome>/caches/production-gradle
```

Cache subdirectories:

```text
minecraft/
assets/
libraries/
loaders/
mods/modrinth/
mods/curseforge/
auth/
metadata/
```

Project directories contain instances only, not reusable download caches.

Default instance roots:

```text
<projectDir>/run-production/client
<projectDir>/run-production/server
```

Mods are copied to:

```text
<instanceDir>/mods
```

## Launch Spec

Each run generates a schema-versioned JSON launch spec.

Example paths:

```text
build/production-gradle/specs/client/launch-spec.json
build/production-gradle/specs/server/launch-spec.json
```

The schema must be designed around real loader launch requirements, not the simplified examples from discussion. It must include enough information for CLI execution without Gradle being present.

Required sections:

- `schemaVersion`
- `run`
- `environment`
- `paths`
- `java`
- `auth`
- `mods`
- `launch`
- `resolutionHints`
- `gradle`

The spec must include:

- run name and type
- Minecraft version
- loader type and version
- Java executable
- Gradle offline flag
- instance, working, cache, assets, libraries, natives, and logs paths
- resolved mod files and source metadata
- auth mode and non-sensitive auth inputs
- JVM args, game args, environment variables, main class override if any
- server EULA state
- adapter hints and fallback artifact metadata

Sensitive Microsoft tokens must not be written in plain text to ordinary launch specs. Auth tokens belong in the Gradle-home auth cache with suitable file permissions.

## Loader Resolution

`launcher-core` defines a loader resolver abstraction:

```text
LoaderResolver
```

Required resolvers:

- Vanilla base resolver
- Fabric resolver
- Forge resolver
- NeoForge resolver

Fabric:

- Resolve official Minecraft metadata.
- Resolve Fabric loader metadata or installer profile.
- Prepare client and server launch classpath and main classes.

Forge:

- Support Minecraft 1.21.1+ Forge production launch.
- Resolve Forge installer or Maven metadata.
- Prepare real client and server production launch state.

NeoForge:

- Support Minecraft 1.21.1+ NeoForge production launch.
- Resolve NeoForge installer or Maven metadata.
- Remain compatible with ModDevGradle and NeoGradle projects by consuming Gradle-resolved mod artifacts, not development run classpaths.

Server:

- Write or validate `eula.txt`.
- `runProductionServer` must fail when EULA is not accepted.
- Start real production server process for Fabric, Forge, and NeoForge.

## Auth

Offline auth:

```groovy
production {
    auth {
        userName = "DevPlayer"
        microsoft false
    }
}
```

Microsoft auth:

```groovy
production {
    auth {
        microsoft true
    }
}
```

Requirements:

- Offline account support must work in MVP.
- Microsoft account support must work in MVP.
- Tokens are cached under Gradle home, not project directories.
- Launch specs reference token cache entries without embedding sensitive token values.
- `print-command` must redact secrets.

## IDEA Integration

Task:

```text
generateProductionIdeaRuns
```

DSL:

```groovy
production {
    idea {
        enabled true
        mode = "gradle"
        overwrite false
    }
}
```

Output directory is fixed:

```text
.idea/runConfigurations
```

No custom output directory is supported.

Modes:

- `gradle`: generate IDEA Gradle run configurations that call `runProductionClient`, `runProductionServer`, or the matching custom run task.
- `application`: generate IDEA Application run configurations that directly invoke the launcher CLI with the generated spec.

Default mode is `gradle`.

Generated files use stable names:

```text
Production_Client.xml
Production_Server.xml
```

For custom run `fooBar`:

```text
Production_FooBar.xml
```

Files generated by the plugin must contain a marker comment. If `overwrite false` and a same-name file exists without the marker, the task fails instead of overwriting user content.

## Error Handling

Configuration-time errors:

- Missing Minecraft version, loader, or loader version after auto-detection and user overrides.
- Multiple ambiguous development artifacts after Gradle resolution and adapter fallback.
- Invalid run type.
- Invalid Java executable.
- Missing CurseForge API key for runs using CurseForge.

Prepare-time errors:

- Missing metadata.
- Checksum mismatch.
- Loader or Minecraft version mismatch.
- Required mod dependency missing.
- Offline cache miss.
- Directory permission failure.

Run-time behavior:

- Game stdout and stderr are streamed to Gradle.
- Logs are also written to `<instanceDir>/logs/production-gradle.log`.
- Game process exit code is propagated to Gradle.
- Secrets are redacted from diagnostics.

Conflict handling:

- Same mod id with multiple versions fails by default.
- Loader mismatch fails.
- Minecraft version mismatch fails.
- Side mismatch fails when metadata is explicit.
- Unknown side metadata produces a warning.
- Duplicate same file is deduplicated.

## Testing

Testing must verify real behavior, not only mocked unit paths.

### Unit Tests

`launcher-core`:

- version metadata parsing
- library rules
- asset index handling
- natives extraction plan
- argument interpolation
- loader resolver command construction
- mod graph dependency resolution

`gradle-plugin`:

- DSL behavior
- task registration
- per-run hidden configurations
- Gradle Toolchains integration
- explicit `javaExecutable` override
- launch spec generation
- IDEA run config generation

`gradle-adapters`:

- Fabric Loom detection
- ForgeGradle detection
- NeoGradle detection
- ModDevGradle detection
- fallback artifact resolution

`mod-providers`:

- Modrinth metadata resolution
- CurseForge metadata resolution
- required dependency recursion
- optional dependency toggle
- loader and Minecraft filtering

### Integration Tests

Use Gradle TestKit and composite builds.

Fixture projects:

```text
fabric-loom-1.21.1
forgegradle-1.21.1
neogradle-1.21.1
moddevgradle-1.21.1
```

Each fixture must verify:

- plugin applies
- auto-detected Minecraft version
- auto-detected loader and loader version
- current project artifact inclusion
- per-run mod configuration isolation
- launch spec generation
- `printProduction...Command`
- `generateProductionIdeaRuns`

### Full E2E Tests

At least one complete E2E path is required.

Required E2E coverage:

- Use the plugin through an included build.
- Resolve Java via Gradle Toolchains or explicit `javaExecutable`.
- Resolve real Minecraft metadata.
- Resolve real loader metadata.
- Resolve at least one real Modrinth dependency.
- Resolve a real CurseForge dependency when `CURSEFORGE_API_KEY` or `production.curseforgeApiKey` is available; otherwise run a real validation-path test that proves the provider fails with the documented missing-key error.
- Generate launch spec.
- Prepare a real production instance.
- Run a real server smoke test to startup readiness and then stop it cleanly.
- Generate IDEA run configurations.

Client E2E must prepare a real client instance and print the final command/classpath. A graphical client launch may be local-only if CI has no graphical environment, but the implementation must support real client launch.

Mocking policy:

- Do not mock Gradle dependency resolution.
- Do not mock adapter detection in integration tests.
- Do not mock launcher command construction.
- Mock external HTTP only in focused failure tests or where third-party service limits would make CI unreliable.

## Implementation Order

1. Create Java multi-module Gradle build and plugin metadata.
2. Implement DSL, run container, hidden configurations, and task registration.
3. Implement Gradle Toolchains Java executable resolution and explicit executable override.
4. Implement launch spec model and JSON serialization.
5. Implement IDEA run configuration generation.
6. Implement Gradle adapters.
7. Implement launcher core metadata, cache, and loader resolvers.
8. Implement mod providers and dependency graph validation.
9. Implement CLI commands.
10. Add integration fixtures and full E2E tests.

## Acceptance Criteria

- Applying `dev.vfyjxf.gradle.production` creates client and server production runs.
- `runProductionClient` starts a real production-style Minecraft client for supported loaders.
- `runProductionServer` starts a real production server for supported loaders when EULA is accepted.
- Development project mods are included as production artifacts.
- Per-run mod sets are independent.
- Gradle variant-aware resolution is used for project and module mod dependencies.
- Users configure production mods inside `production {}`.
- Gradle `--offline` is honored by Gradle and CLI.
- Reusable cache defaults under Gradle user home.
- Java runtime selection uses Gradle Toolchains by default.
- Explicit per-run `javaExecutable` works.
- Modrinth and CurseForge required dependencies resolve.
- Microsoft and offline auth work.
- IDEA run configurations are generated under `.idea/runConfigurations`.
- Composite build tests cover the supported development plugins.
- A complete E2E server smoke test passes.
