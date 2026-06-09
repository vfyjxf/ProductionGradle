# ForgeGradle 1.21.1 ProductionGradle Example

This is a complete ForgeGradle project for Minecraft 1.21.1. It uses this repository through `includeBuild("../..")`, so local ProductionGradle changes are used directly.

## What It Demonstrates

- A normal ForgeGradle development project.
- ProductionGradle auto-detection of Minecraft and Forge versions.
- The current project mod being included in production runs through the reobfuscated jar fallback when available.
- CurseForge JEI as a client-only production mod.
- Client, Microsoft-auth client, and server production runs.
- IDEA application run configuration import during IntelliJ Gradle sync.

## Try It

ForgeGradle 6 does not support Gradle 9 yet, so this example has its own Gradle 8 wrapper. ForgeGradle's setup tasks are not configuration-cache compatible, so this example disables Gradle configuration cache in `gradle.properties`.

From this example directory:

```bash
cd examples/forge-gradle-production-1.21.1
./gradlew tasks --group production
./gradlew runProductionClient
./gradlew runProductionServer
```

ProductionGradle caches runtime files under:

```text
<Gradle user home>/caches/production-gradle
```

To keep the cache local to this repository while experimenting:

```bash
./gradlew --gradle-user-home ../../.gradle runProductionClient
```

CurseForge downloads require `production.curseforgeApiKey` in Gradle properties or `CURSEFORGE_API_KEY` in the environment.
