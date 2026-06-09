# ProductionGradle

ProductionGradle 是一个 Gradle 插件，用于直接从模组开发工程启动接近真实生产环境的 Minecraft。它会生成启动规格文件，解析 Minecraft 运行时文件到 Gradle user home 缓存，装配当前工程和远程下载的 mod，注册 Gradle 运行任务，并在 IntelliJ IDEA 同步 Gradle 工程时贡献 IDEA Application 运行配置。

ProductionGradle 使用 Java 编写，目标是 Fabric Loom、ForgeGradle、NeoGradle 和 NeoForge ModDevGradle 等模组开发工程。

English documentation: [README.md](README.md)

## 状态

这是一个早期的生产环境运行插件。目前核心客户端和服务端路径已覆盖：

- Vanilla
- Fabric
- Forge
- NeoForge

## 功能

- 从 Gradle 启动接近生产环境的 Minecraft 客户端和服务端。
- 按 run 单独配置的 Gradle DSL，例如 `userName = "DevPlayer"`。
- 尽可能从开发插件自动识别 Minecraft 和 loader 版本。
- 每个 run 独立配置 mod。
- 装配当前工程产物，并支持 remapped、reobfuscated、production jar 等开发插件产物回退。
- 通过 `mods { add(...) }` 使用 Gradle 自己的依赖解析处理 mod classpath。
- 在生成 launch spec 时从 Modrinth 和 CurseForge 下载远程 mod。
- Minecraft 运行时缓存固定在 `<Gradle user home>/caches/production-gradle`。
- 离线模式服从 Gradle 自己的 `--offline`。
- 通过 Gradle Java Toolchains 选择 Java 运行时，也支持按 run 指定 `javaExecutable`。
- 默认离线登录，按 run 可开启基于 DevLogin 的 Microsoft 正版登录。
- IntelliJ IDEA 同步时生成真正的 IDEA Application 运行配置。

## 插件 ID

```groovy
plugins {
    id "dev.vfyjxf.gradle.production"
}
```

仓库内示例通过 `includeBuild("../..")` 使用当前源码中的插件。

## 快速开始

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

运行：

```bash
./gradlew tasks --group production
./gradlew runProductionClient
./gradlew runProductionServer
```

## `production` 配置块

ProductionGradle 添加了顶层 `production` extension：

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

顶层属性：

- `autoDetect`：启用开发环境识别，默认 `true`。
- `instanceDir`：默认实例目录根路径，默认是项目目录下的 `run-production`。
- `cacheDir`：运行时缓存目录，默认是 `<Gradle user home>/caches/production-gradle`。
- `idea`：IDEA 运行配置设置。
- `runs`：生产环境 run 容器。

## Runs

每个 run 独立配置：

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

run 属性：

- `type`：`client` 或 `server`。
- `minecraftVersion`：Minecraft 版本，例如 `1.21.1` 或 `26.1.2`。
- `loader`：`vanilla`、`fabric`、`forge` 或 `neoforge`。
- `loaderVersion`：loader 版本，Vanilla 不需要。
- `instanceDir`：该 run 的游戏实例目录。
- `workingDir`：进程工作目录，默认等于 `instanceDir`。
- `cacheDir`：该 run 使用的运行时缓存目录。
- `javaVersion`：Java toolchain 版本。
- `javaExecutable`：精确指定 Java 可执行文件，会覆盖 `javaVersion`。
- `jvmArgs`：JVM 参数。
- `gameArgs`：游戏参数。
- `environment`：环境变量。
- `mainClass`：可选的主类覆盖。
- `eula`：服务端 run 设置为 `true` 时写入 `eula.txt`。
- `userName`：离线登录用户名。
- `microsoftAuth`：为该 run 开启 Microsoft 登录，默认 `false`。

插件会默认创建 `client` 和 `server` 两个 run。也可以自定义 run 名称，任务名后缀会来自 run 名称的首字母大写形式。

## 任务

对于名为 `client` 的 run，ProductionGradle 会注册：

- `validateProductionClient`
- `prepareProductionClient`
- `runProductionClient`
- `printProductionClientLaunchSpec`
- `printProductionClientCommand`
- `printProductionClientClasspath`

名为 `server` 的 run 会注册对应的 `Server` 任务。

`validateProductionRun` 是 `validateProductionClient` 的便捷别名。

## 开发环境识别

`autoDetect true` 会让 ProductionGradle 尽可能从支持的开发插件中读取 Minecraft 和 loader 信息。简单项目可以只写少量配置：

```groovy
production {
    autoDetect true

    runs.configureEach {
        javaVersion = 21
        userName = "DevPlayer"
    }
}
```

每个 run 上手动设置的属性总是优先生效。

识别逻辑隔离在 `gradle-adapters` 模块中，目前面向 Fabric Loom、ForgeGradle、NeoGradle 和 NeoForge ModDevGradle 形态的工程。

## Java 运行时

ProductionGradle 使用 Gradle Java Toolchains 处理 `javaVersion`：

```groovy
production {
    runs.configureEach {
        javaVersion = 21
    }
}
```

也可以指定精确的 Java 可执行文件：

```groovy
production {
    runs {
        client {
            javaExecutable = file("/path/to/java")
        }
    }
}
```

启动器后端不会自己下载 JDK。Toolchain 解析完全服从 Gradle 自己的配置和已安装的 toolchain resolver。

## Mods

mod 在每个 run 内配置：

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

`includeProject` 默认是 `true`。开启后，ProductionGradle 会包含当前工程的生产产物。如果支持的开发插件暴露了更合适的生产产物任务，例如 `remapJar`、`reobfJar` 或 `productionJar`，ProductionGradle 会使用这些任务输出。

`add(...)` 会通过该 run 的隐藏 mod classpath configuration 交给 Gradle 依赖解析处理。需要选择变体时，使用普通 Gradle project dependency notation 和 configuration selection。

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

Modrinth 下载缓存位置：

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

CurseForge 需要 API key。可以使用：

```properties
production.curseforgeApiKey=your-api-key
```

放在 Gradle properties 文件中，或使用环境变量：

```bash
export CURSEFORGE_API_KEY=your-api-key
```

在 GitHub Actions 中，建议定义 `CURSEFORGE_API_KEY` secret，并在 Gradle step 中暴露为环境变量。不要提交 API key。

CurseForge 下载缓存位置：

```text
<Gradle user home>/caches/production-gradle/mods/curseforge
```

## 登录

默认是离线登录：

```groovy
production {
    runs.configureEach {
        userName = "DevPlayer"
    }
}
```

Microsoft 正版登录按客户端 run 开启：

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

ProductionGradle 将 Microsoft 登录委托给 DevLogin。Token 数据内部存放在：

```text
<Gradle user home>/caches/production-gradle/auth/devlogin
```

DSL 只暴露是否为某个 run 开启 Microsoft 登录，不暴露 token。打印命令时会隐藏 access-token 风格的参数。

## 缓存与离线模式

默认缓存位置是：

```text
<Gradle user home>/caches/production-gradle
```

它跟随 Gradle user home：

```bash
./gradlew --gradle-user-home .gradle prepareProductionClient
```

ProductionGradle 没有单独的 `offline` DSL。请使用 Gradle 自己的离线模式：

```bash
./gradlew --offline prepareProductionClient
```

离线模式要求 Minecraft metadata、jar、libraries、assets、natives、loader metadata 和远程 mod 文件都已经存在于 ProductionGradle 缓存中。

## IntelliJ IDEA

应用 `idea` 插件，并在 IntelliJ IDEA 中同步 Gradle 工程：

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

Application mode 是默认模式。它会创建真正的 IDEA Application 运行配置，调用 IDE 模块中的 production launcher bridge。运行配置会依赖对应的 launch spec 生成任务和 launcher bridge classes。

如果更希望 IDEA 配置调用 Gradle 任务，也可以使用 `mode = "gradle"`。

IDEA 负责最终 run configuration 的存储位置。ProductionGradle 在 IDEA Gradle sync 期间通过 Gradle IDEA model 贡献配置，不暴露公开的文件生成任务。

## 示例工程

仓库包含以下示例：

- [examples/neoforge-mdg-production/](examples/neoforge-mdg-production/)：Minecraft 1.21.1 的 NeoForge ModDevGradle 工程。
- [examples/neoforge-mdg-production-26.1.2/](examples/neoforge-mdg-production-26.1.2/)：Minecraft 26.1.2 的 NeoForge ModDevGradle 工程。
- [examples/fabric-loom-production-1.21.1/](examples/fabric-loom-production-1.21.1/)：Minecraft 1.21.1 的 Fabric Loom 工程。
- [examples/fabric-loom-production-26.1.2/](examples/fabric-loom-production-26.1.2/)：Minecraft 26.1.2 的 Fabric Loom 工程。
- [examples/forge-gradle-production-1.21.1/](examples/forge-gradle-production-1.21.1/)：Minecraft 1.21.1 的 ForgeGradle 工程。

Fabric 示例展示 Modrinth Fabric API 和 CurseForge JEI。Forge 与 NeoForge 示例展示 CurseForge JEI。所有示例都使用 `includeBuild("../..")`，因此会直接使用当前仓库源码中的插件。

## 项目模块

- `launcher-core`：launch spec 模型、校验、缓存布局、Vanilla/Fabric/Forge/NeoForge 解析、下载、登录集成和进程命令构造。
- `launcher-cli`：Gradle 任务调用的小型 CLI，用于 validate、prepare、run 和打印启动信息。
- `gradle-plugin`：Gradle DSL、任务、toolchain 集成、IDEA 集成和 launch spec 生成。
- `gradle-adapters`：Fabric Loom、ForgeGradle、NeoGradle 和 ModDevGradle 的开发环境识别。
- `mod-providers`：Modrinth 和 CurseForge provider 解析。
- `integration-tests`：fixture 和端到端测试。

## 协议

ProductionGradle 使用 MIT License。见 [LICENSE](LICENSE)。
