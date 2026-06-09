# NeoForge ModDevGradle ProductionGradle Example

This is a complete manual example project based on the NeoForge 1.21.1 ModDevGradle MDK shape. It uses this repository through `includeBuild("../..")`, so local changes to ProductionGradle are used directly.

## What It Demonstrates

- A normal ModDevGradle development project using `net.neoforged.moddev`.
- ProductionGradle auto-detection of Minecraft and NeoForge versions.
- The current project mod being included in production runs.
- CurseForge JEI as a client-only production mod.
- Production client and server runs using `runProductionClient` and `runProductionServer`.
- IDEA run configuration import during IntelliJ Gradle sync.

## Try It

From the repository root:

```bash
./gradlew -p examples/neoforge-mdg-production tasks --group production
./gradlew -p examples/neoforge-mdg-production runClient
./gradlew -p examples/neoforge-mdg-production runProductionClient
./gradlew -p examples/neoforge-mdg-production runProductionServer
```

The first Gradle invocation may download NeoForge, Minecraft metadata, libraries, assets, and the selected Java toolchain if your machine does not already have them.

ProductionGradle caches runtime files under:

```text
<Gradle user home>/caches/production-gradle
```

To keep the cache local to this repository while experimenting:

```bash
./gradlew --gradle-user-home .gradle -p examples/neoforge-mdg-production runProductionClient
```

The server run accepts the EULA for this disposable example and writes its production instance under `run-production/server`.

CurseForge downloads require `production.curseforgeApiKey` in Gradle properties or `CURSEFORGE_API_KEY` in the environment.
