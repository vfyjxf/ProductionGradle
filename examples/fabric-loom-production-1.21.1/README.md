# Fabric Loom 1.21.1 ProductionGradle Example

This example is based on the official `FabricMC/fabric-example-mod` `1.21.1` branch, with the local `dev.vfyjxf.gradle.production` plugin added through `includeBuild("../..")`.

It keeps the official Fabric template structure and demonstrates ProductionGradle client, Microsoft-auth client, and server runs for Minecraft 1.21.1.

## Try It

From the repository root:

```bash
./gradlew -p examples/fabric-loom-production-1.21.1 tasks --group production
./gradlew -p examples/fabric-loom-production-1.21.1 runProductionClient
./gradlew -p examples/fabric-loom-production-1.21.1 runProductionServer
```

ProductionGradle caches runtime files under:

```text
<Gradle user home>/caches/production-gradle
```

To keep the cache local to this repository while experimenting:

```bash
./gradlew --gradle-user-home .gradle -p examples/fabric-loom-production-1.21.1 runProductionClient
```

The example also includes the upstream Gradle wrapper from the Fabric template. Use it when you want the exact Gradle version expected by the Fabric template:

```bash
./gradlew --gradle-user-home ../../.gradle tasks --group production
```
