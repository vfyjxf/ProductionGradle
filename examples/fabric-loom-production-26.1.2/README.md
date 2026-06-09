# Fabric Loom 26.1.2 ProductionGradle Example

This example is based on the official `FabricMC/fabric-example-mod` `26.1.2` branch, with the local `dev.vfyjxf.gradle.production` plugin added through `includeBuild("../..")`.

It keeps the official Fabric template structure and demonstrates ProductionGradle client, Microsoft-auth client, and server runs for Minecraft 26.1.2.

The production client runs also demonstrate remote mod providers:

- Fabric API from Modrinth.
- JEI from CurseForge.

CurseForge downloads require `production.curseforgeApiKey` in Gradle properties or `CURSEFORGE_API_KEY` in the environment.

## Try It

From the repository root:

```bash
./gradlew -p examples/fabric-loom-production-26.1.2 tasks --group production
./gradlew -p examples/fabric-loom-production-26.1.2 runProductionClient
./gradlew -p examples/fabric-loom-production-26.1.2 runProductionServer
```

ProductionGradle caches runtime files under:

```text
<Gradle user home>/caches/production-gradle
```

To keep the cache local to this repository while experimenting:

```bash
./gradlew --gradle-user-home .gradle -p examples/fabric-loom-production-26.1.2 runProductionClient
```

The example also includes the upstream Gradle wrapper from the Fabric template, so it can be run directly from this directory:

```bash
./gradlew --gradle-user-home ../../.gradle tasks --group production
```
