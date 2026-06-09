# NeoForge ModDevGradle 26.1.2 ProductionGradle Example

This is a complete NeoForge ModDevGradle project for Minecraft 26.1.2. It uses this repository through `includeBuild("../..")`, so local ProductionGradle changes are used directly.

## What It Demonstrates

- A normal ModDevGradle development project using `net.neoforged.moddev`.
- ProductionGradle auto-detection of Minecraft and NeoForge versions on Mojang's 26.1 version line.
- Java 25 toolchain selection for 26.1.2 production runs.
- CurseForge JEI as a client-only production mod.
- Client, Microsoft-auth client, and server production runs.
- IDEA application run configuration import during IntelliJ Gradle sync.

## Try It

From the repository root:

```bash
./gradlew -p examples/neoforge-mdg-production-26.1.2 tasks --group production
./gradlew -p examples/neoforge-mdg-production-26.1.2 runProductionClient
./gradlew -p examples/neoforge-mdg-production-26.1.2 runProductionServer
```

ProductionGradle caches runtime files under:

```text
<Gradle user home>/caches/production-gradle
```

To keep the cache local to this repository while experimenting:

```bash
./gradlew --gradle-user-home .gradle -p examples/neoforge-mdg-production-26.1.2 runProductionClient
```

CurseForge downloads require `production.curseforgeApiKey` in Gradle properties or `CURSEFORGE_API_KEY` in the environment.
