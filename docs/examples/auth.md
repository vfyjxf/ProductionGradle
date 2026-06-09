# Authentication

## Offline Auth

Offline auth is the default. It is useful for local development and production-style smoke tests that do not need Microsoft account services.

```groovy
production {
    runs.configureEach {
        userName = "DevPlayer"
    }
}
```

Offline auth is the default for every run. It sets the launch spec auth mode to `offline`; vanilla launch arguments use the run user name, a deterministic offline UUID, and an offline access token placeholder.

## Microsoft Auth With DevLogin

Microsoft auth is delegated to covers1624 DevLogin. ProductionGradle wraps the resolved client main class with DevLogin and points DevLogin storage at:

```text
<Gradle user home>/caches/production-gradle/auth/devlogin
```

The Gradle DSL can mark specs as Microsoft auth:

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

Microsoft auth is configured per run and is off by default. Token cache keys are intentionally not exposed in the Gradle DSL; the launcher stores token data internally under Gradle user home.

Microsoft auth is only valid for client runs. Server runs are rejected if `microsoftAuth = true`.

`printProductionClientCommand` and `printProductionServerCommand` redact access-token style arguments before printing commands.
