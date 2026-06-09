# ProductionGradle

ProductionGradle is a Java Gradle plugin for preparing and launching production Minecraft runs from a Gradle build. It creates production-style client and server launch specs, resolves Minecraft runtime files into the Gradle user home cache, exposes Gradle tasks for preparing, running, and printing commands, and contributes IntelliJ IDEA run configurations during Gradle sync.

The plugin id is:

```groovy
plugins {
    id "dev.vfyjxf.gradle.production"
}
```

## Modules

- `launcher-core`: launch spec model, validation, cache layout, vanilla/Fabric/Forge/NeoForge resolution, process command creation.
- `launcher-cli`: small CLI used by Gradle tasks to validate, prepare, run, and print launch details.
- `gradle-plugin`: Gradle DSL and tasks.
- `gradle-adapters`: development environment detection for Fabric Loom, ForgeGradle, NeoGradle, and ModDevGradle.
- `mod-providers`: Modrinth and CurseForge provider resolution used by the Gradle run DSL.
- `integration-tests`: fixture and E2E coverage.

## Basic DSL

```groovy
plugins {
    id "java"
    id "dev.vfyjxf.gradle.production"
}

production {
    instanceDir = file("run-production")

    idea {
        overwrite = true
        mode = "application"
    }

    runs.configureEach {
        minecraftVersion = "1.21.1"
        loader = "vanilla"
        javaVersion = 21
        userName = "DevPlayer"
    }

    runs {
        client {
            type = "client"
            jvmArgs "-Xmx2G"
        }
        server {
            type = "server"
            eula true
            jvmArgs "-Xmx1G"
            gameArgs "nogui"
        }
        microsoftClient {
            type = "client"
            microsoftAuth = true
            jvmArgs "-Xmx2G"
        }
    }
}
```

Auto-detection is enabled by default. The plugin reads Minecraft and loader versions from supported development plugins when possible, and manual values on each run still win when you set them directly. Set `autoDetect false` only when you want to disable development environment detection.

## Tasks

- `prepareProductionClient`: downloads/resolves runtime files and writes client directories.
- `runProductionClient`: prepares and launches the client.
- `prepareProductionServer`: downloads/resolves the server jar and writes `eula.txt` when `eula true`.
- `runProductionServer`: prepares and launches the server.
- `printProductionClientCommand` / `printProductionServerCommand`: prints the redacted Java command.
- `printProductionClientClasspath` / `printProductionServerClasspath`: prints resolved classpath entries.

`validateProductionRun` is a convenience alias for `validateProductionClient`.

IntelliJ IDEA run configurations are registered through the Gradle IDEA model during IntelliJ Gradle sync. The generation task is internal and is not exposed as a user-facing Gradle task.

## Cache And Offline Mode

By default, runtime files are cached under:

```text
<Gradle user home>/caches/production-gradle
```

This follows the Gradle user home used by the build. For example:

```bash
./gradlew --gradle-user-home .gradle prepareProductionClient
```

There is no separate `offline` DSL flag. Use Gradle's own offline mode:

```bash
./gradlew --offline prepareProductionClient
```

Offline mode requires the needed Minecraft metadata, jars, libraries, assets, natives, and mod files to already exist in the ProductionGradle cache.

## Java Runtime

Runs use Gradle Java Toolchains through `javaVersion`:

```groovy
production {
    runs.configureEach {
        javaVersion = 21
    }
}
```

You can also set an exact executable per run:

```groovy
production {
    runs {
        client {
            javaExecutable = file("/path/to/java")
        }
    }
}
```

## Mods

Mods are configured inside each production run:

```groovy
production {
    runs {
        client {
            mods {
                add project(":client-mod")
                add "maven.modrinth:sodium:mc1.21.1-0.6.13-fabric"
                modrinth("sodium") {
                    version = "mc1.21.1-0.6.13"
                }
            }
        }
    }
}
```

The current project is included by default. When a supported development plugin exposes a production artifact task such as `remapJar`, `reobfJar`, or `productionJar`, ProductionGradle uses that task output as a convenience fallback for the current project. The `mods { add(...) }` method delegates to Gradle dependency resolution for that run's hidden mod classpath configuration. Use Gradle project dependency attributes and configuration selection when you need a specific variant, for example `add project(path: ":mod", configuration: "productionElements")`.

Remote provider requests are also resolved during launch spec generation and cached under the configured Gradle user home cache directory. `modrinth("slug") { version = "..." }`, `modrinthVersion("version-id")`, and `curseforge("name") { projectId = 123; fileId = 456 }` are supported. CurseForge requires `-Pproduction.curseforgeApiKey=...` or `CURSEFORGE_API_KEY`.

## Examples

See:

- `examples/neoforge-mdg-production/`: complete NeoForge ModDevGradle project for manual production-run testing.
- `examples/fabric-loom-production-1.21.1/`: complete Fabric Loom project for Minecraft 1.21.1.
- `examples/forge-gradle-production-1.21.1/`: complete ForgeGradle project for Minecraft 1.21.1.
- `examples/fabric-loom-production-26.1.2/`: complete Fabric Loom project for Minecraft 26.1.2.
- `examples/neoforge-mdg-production-26.1.2/`: complete NeoForge ModDevGradle project for Minecraft 26.1.2.
- `docs/examples/fabric.gradle`
- `docs/examples/neoforge.gradle`
- `docs/examples/forge.gradle`
- `docs/examples/idea.md`
- `docs/examples/auth.md`
