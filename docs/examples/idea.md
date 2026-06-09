# IntelliJ IDEA Run Configurations

ProductionGradle contributes IDEA run configurations through the Gradle IDEA model during IntelliJ Gradle sync. Reimport or sync the Gradle project in IDEA after changing `production.runs`.

There is no public Gradle task to generate these files. IDEA owns the final storage location for imported run configurations.

## Application Mode

Application mode is the default. It creates IDEA application configurations that call the launcher CLI directly after generating the corresponding launch spec.

```groovy
production {
    idea {
        enabled = true
        mode = "application"
        overwrite = true
    }
}
```

## Gradle Mode

Gradle mode creates IDEA configurations that call Gradle tasks such as `runProductionClient` and `runProductionServer`.

```groovy
production {
    idea {
        enabled = true
        mode = "gradle"
        overwrite = true
    }
}
```

ProductionGradle refuses to replace an existing IDEA run configuration with the same name unless `overwrite = true` is set.
