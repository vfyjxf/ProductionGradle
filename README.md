# ProductionGradle

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/dev.vfyjxf.gradle.production?label=plugin%20portal)](https://plugins.gradle.org/plugin/dev.vfyjxf.gradle.production)

ProductionGradle is a Gradle plugin for running Minecraft in a production-like environment directly from mod development builds. It generates launch specifications, resolves Minecraft runtime files into the Gradle user home cache, stages project and remote mods, creates Gradle run tasks, and contributes IntelliJ IDEA application run configurations during Gradle sync.

ProductionGradle is written in Java and is intended for mod projects that use Fabric Loom, ForgeGradle, NeoGradle, or NeoForge ModDevGradle.

Chinese documentation: [README.zh-CN.md](README.zh-CN.md)

## Status

This project is an early production-run launcher plugin. The core client/server paths are implemented for:

- Vanilla
- Fabric
- Forge
- NeoForge

## Features

- Production-style Minecraft client and server launches from Gradle.
- Per-run Gradle DSL using idiomatic property assignment, for example `userName = "DevPlayer"`.
- Auto-detection of Minecraft and loader versions from supported development plugins when possible.
- Per-run mod configuration.
- Current project artifact staging, including development-plugin production artifact fallbacks such as remapped or reobfuscated jars.
- Gradle dependency based mod classpaths through `mods { add(...) }`.
- Modrinth and CurseForge remote mod downloads during launch spec generation.
- Minecraft runtime cache under `<Gradle user home>/caches/production-gradle`.
- Gradle offline mode support through Gradle's own `--offline` flag.
- Java runtime selection through Gradle Java Toolchains, with per-run executable override support.
- Offline auth by default and optional per-run Microsoft auth through DevLogin.
- IntelliJ IDEA application run configurations generated through the Gradle IDEA model during IDEA sync.

## Plugin ID

```groovy
plugins {
    id "dev.vfyjxf.gradle.production"
}
```

When working from this repository's examples, the plugin is supplied through `includeBuild("../..")`.

## Quick Start

```groovy
plugins {
    id "java"
    id "dev.vfyjxf.gradle.production"
}

production {
    runs.configureEach {
        minecraftVersion = "1.21.1"
        loader = "fabric"
        loaderVersion = "0.16.10"
        javaVersion = 21
        userName = "DevPlayer"
    }

    runs {
        client {
            type = "client"
            instanceDir = file("run-production/client")
            jvmArgs "-Xmx2G"
        }

        server {
            type = "server"
            instanceDir = file("run-production/server")
            eula true
            jvmArgs "-Xmx2G"
            gameArgs "nogui"
        }
    }
}
```

Run:

```bash
./gradlew tasks --group production
./gradlew runProductionClient
./gradlew runProductionServer
```

## The `production` Block

ProductionGradle adds a top-level `production` extension:

```groovy
production {
    autoDetect true
    instanceDir = file("run-production")

    idea {
        enabled = true
        mode = "application"
        overwrite = true
    }

    runs {
        client {
            type = "client"
        }
    }
}
```

Top-level properties:

- `autoDetect`: Enables development environment detection. Defaults to `true`.
- `instanceDir`: Default root instance directory. Defaults to `run-production` under the project directory.
- `cacheDir`: Runtime cache directory. Defaults to `<Gradle user home>/caches/production-gradle`.
- `idea`: IDEA run configuration settings.
- `runs`: Named production run container.

## Runs

Each run is configured independently:

```groovy
production {
    runs {
        client {
            type = "client"
            minecraftVersion = "1.21.1"
            loader = "neoforge"
            loaderVersion = "21.1.233"
            instanceDir = file("run-production/client")
            workingDir = file("run-production/client")
            javaVersion = 21
            userName = "DevPlayer"
            jvmArgs "-Xmx2G"
        }

        server {
            type = "server"
            eula true
            gameArgs "nogui"
        }
    }
}
```

Run properties:

- `type`: `client` or `server`.
- `minecraftVersion`: Minecraft version, for example `1.21.1` or `26.1.2`.
- `loader`: `vanilla`, `fabric`, `forge`, or `neoforge`.
- `loaderVersion`: Loader version. Vanilla runs do not require this.
- `instanceDir`: The run's game instance directory.
- `workingDir`: Process working directory. Defaults to `instanceDir`.
- `cacheDir`: Runtime cache directory for this run.
- `javaVersion`: Java toolchain version.
- `javaExecutable`: Exact Java executable file. This overrides `javaVersion`.
- `jvmArgs`: JVM arguments.
- `gameArgs`: Game arguments.
- `environment`: Environment variables.
- `mainClass`: Optional explicit main class override.
- `eula`: Writes `eula.txt` for server runs when set to `true`.
- `userName`: Offline user name.
- `microsoftAuth`: Enables Microsoft auth for that run. Defaults to `false`.

The plugin creates default `client` and `server` runs. Custom run names are supported and become task suffixes after capitalization.

## Tasks

For a run named `client`, ProductionGradle registers:

- `validateProductionClient`
- `prepareProductionClient`
- `runProductionClient`
- `printProductionClientLaunchSpec`
- `printProductionClientCommand`
- `printProductionClientClasspath`

For a run named `server`, equivalent `Server` tasks are registered.

`validateProductionRun` is a convenience alias for `validateProductionClient`.

## Development Environment Detection

`autoDetect true` lets ProductionGradle read Minecraft and loader information from supported development plugins when possible. This keeps simple projects concise:

```groovy
production {
    autoDetect true

    runs.configureEach {
        javaVersion = 21
        userName = "DevPlayer"
    }
}
```

Manual run properties always win over detected values.

Detection logic is isolated in the `gradle-adapters` module and currently targets Fabric Loom, ForgeGradle, NeoGradle, and NeoForge ModDevGradle style projects.

## Java Runtime

ProductionGradle uses Gradle Java Toolchains for `javaVersion`:

```groovy
production {
    runs.configureEach {
        javaVersion = 21
    }
}
```

You can also supply an exact executable:

```groovy
production {
    runs {
        client {
            javaExecutable = file("/path/to/java")
        }
    }
}
```

The launcher backend does not download JDKs itself. Toolchain resolution follows Gradle's own configuration and installed toolchain resolvers.

## Mods

Mods are configured inside each run:

```groovy
production {
    runs {
        client {
            mods {
                includeProject true
                includeRequiredDependencies true
                includeOptionalDependencies false

                add project(":shared-mod")
                add project(path: ":variant-mod", configuration: "productionElements")
                add tasks.named("remapJar")
                add files("mods/local-test-mod.jar")
            }
        }
    }
}
```

`includeProject` defaults to `true`. When enabled, ProductionGradle includes the current project's production artifact. If a supported development plugin exposes a better production artifact task, such as `remapJar`, `reobfJar`, or `productionJar`, ProductionGradle uses that output.

`add(...)` delegates to Gradle dependency resolution through the run's hidden mod classpath configuration. Use normal Gradle project dependency notation and configuration selection for variants.

### Modrinth

```groovy
production {
    runs {
        client {
            mods {
                modrinth("fabric-api") {
                    version = "0.116.12+1.21.1"
                }

                modrinthVersion("version-id")
            }
        }
    }
}
```

Modrinth downloads are cached under:

```text
<Gradle user home>/caches/production-gradle/mods/modrinth
```

### CurseForge

```groovy
production {
    runs {
        client {
            mods {
                curseforge("jei") {
                    projectId = 238222
                    fileId = 7420587
                }
            }
        }
    }
}
```

CurseForge requires an API key. Use either:

```properties
production.curseforgeApiKey=your-api-key
```

in a Gradle properties file, or:

```bash
export CURSEFORGE_API_KEY=your-api-key
```

For GitHub Actions, define a secret such as `CURSEFORGE_API_KEY` and expose it as an environment variable for the Gradle step. Do not commit API keys.

CurseForge downloads are cached under:

```text
<Gradle user home>/caches/production-gradle/mods/curseforge
```

## Authentication

Offline auth is the default:

```groovy
production {
    runs.configureEach {
        userName = "DevPlayer"
    }
}
```

Microsoft auth is configured per client run:

```groovy
production {
    runs {
        microsoftClient {
            type = "client"
            microsoftAuth = true
        }
    }
}
```

ProductionGradle delegates Microsoft auth to DevLogin. Token data is stored internally under:

```text
<Gradle user home>/caches/production-gradle/auth/devlogin
```

The DSL only exposes whether Microsoft auth is enabled for a run. Tokens are not exposed in the Gradle model. Printed commands redact access-token style arguments.

## Cache And Offline Mode

The default cache location is:

```text
<Gradle user home>/caches/production-gradle
```

This follows the Gradle user home used by the build:

```bash
./gradlew --gradle-user-home .gradle prepareProductionClient
```

ProductionGradle does not provide a separate `offline` DSL flag. Use Gradle's own offline mode:

```bash
./gradlew --offline prepareProductionClient
```

Offline mode requires all needed Minecraft metadata, jars, libraries, assets, natives, loader metadata, and remote mod files to already exist in the ProductionGradle cache.

## IntelliJ IDEA

Apply the `idea` plugin and sync the Gradle project in IntelliJ IDEA:

```groovy
plugins {
    id "idea"
    id "dev.vfyjxf.gradle.production"
}

production {
    idea {
        enabled = true
        mode = "application"
        overwrite = true
    }
}
```

Application mode is the default. It creates real IDEA application run configurations that call the generated production launcher bridge in the IDE module. The run configuration depends on the launch spec generation task and the launcher bridge classes.

`mode = "gradle"` is also available when you prefer IDEA configurations that invoke Gradle tasks.

IDEA owns the final run configuration storage location. ProductionGradle contributes configurations through the Gradle IDEA model during IDEA sync; it does not expose a public file-generation task.

## Examples

Included examples:

- [examples/neoforge-mdg-production/](examples/neoforge-mdg-production/): NeoForge ModDevGradle project for Minecraft 1.21.1.
- [examples/neoforge-mdg-production-26.1.2/](examples/neoforge-mdg-production-26.1.2/): NeoForge ModDevGradle project for Minecraft 26.1.2.
- [examples/fabric-loom-production-1.21.1/](examples/fabric-loom-production-1.21.1/): Fabric Loom project for Minecraft 1.21.1.
- [examples/fabric-loom-production-26.1.2/](examples/fabric-loom-production-26.1.2/): Fabric Loom project for Minecraft 26.1.2.
- [examples/forge-gradle-production-1.21.1/](examples/forge-gradle-production-1.21.1/): ForgeGradle project for Minecraft 1.21.1.

The Fabric examples demonstrate Modrinth Fabric API plus CurseForge JEI. The Forge and NeoForge examples demonstrate CurseForge JEI. All examples use `includeBuild("../..")` so local plugin changes are used directly.

## Project Modules

- `launcher-core`: launch spec model, validation, cache layout, vanilla/Fabric/Forge/NeoForge resolution, downloads, auth integration, and process command creation.
- `launcher-cli`: small CLI used by Gradle tasks to validate, prepare, run, and print launch details.
- `gradle-plugin`: Gradle DSL, tasks, toolchain integration, IDEA integration, and launch spec generation.
- `gradle-adapters`: development environment detection for Fabric Loom, ForgeGradle, NeoGradle, and ModDevGradle.
- `mod-providers`: Modrinth and CurseForge provider resolution.
- `integration-tests`: fixture and end-to-end coverage.

## License

ProductionGradle is licensed under the MIT License. See [LICENSE](LICENSE).
