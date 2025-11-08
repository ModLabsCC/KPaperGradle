# KPaperGradle

<div align="center">

[![Build Status](https://img.shields.io/github/actions/workflow/status/ModLabsCC/KPaperGradle/publish.yml?branch=main)](https://github.com/ModLabsCC/KPaperGradle/actions)
[![License](https://img.shields.io/github/license/ModLabsCC/KPaperGradle)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/gradle-plugin-green.svg?logo=gradle)](https://gradle.org)

*A Gradle plugin that streamlines using KPaper in your PaperMC plugins*

[🚀 Quick Start](#-quick-start) • [✨ Features](#-key-features) • [🧩 How-It-Works](#-how-it-works) • [🛠 configuration](#-configuration) • [🤝 Contributing](#-contributing)

</div>

---

KPaperGradle automates integrating [KPaper](https://github.com/ModLabsCC/KPaper) into your [Paper](https://papermc.io/) projects. It adds the KPaper dependency, generates the required bootstrap/registration classes, wires resource processing, and provides a simple DSL to declare additional libraries that are delivered at runtime via Paper's plugin loader.

## ✨ Key Features

- **📦 One‑line KPaper dependency** — Adds `cc.modlabs:KPaper:<version>` automatically
- **🛠 Auto toolchain** — Configures Java toolchain/`--release` from a single `javaVersion` setting
- **🧬 Source generation** — Generates `CommandBootstrapper`, `DependencyLoader`, and `RegisterManager`
- **🧾 Compliance file** — Produces `.dependencies` and places it in your plugin resources
- **🧩 Paper integration** — Patches `paper-plugin.yml` to include `loader` and `bootstrapper` if missing
- **📚 Convention‑based registration** — Auto‑registers commands; discovers listeners (manual call required)
- **🚚 Deliver extra libs** — Simple `deliver("group:artifact:version")` DSL for runtime libraries

## 🚀 Quick Start

Add the plugin to your Paper plugin project.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://nexus.modlabs.cc/repository/maven-public/")
        mavenLocal()
    }
}
```

```kotlin
// build.gradle.kts (of your Paper plugin)
plugins {
    kotlin("jvm") version "2.0.0"
    id("cc.modlabs.kpaper-gradle") version "LATEST" // replace with latest version
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://nexus.modlabs.cc/repository/maven-mirrors/")
}

kpaper {
    // Target Java version (configures toolchain + compiler --release)
    javaVersion.set(21)

    // Base package scanned for registration (commands/listeners)
    registrationBasePackage.set("com.example.myplugin")

    // Libraries to deliver at runtime through the Paper loader
    deliver(
        "com.github.ben-manes.caffeine:caffeine:3.1.8"
    )
}
```

That’s it. Build your plugin as usual — the generated classes and resources are wired into the build.

## 💡 Examples

<details>
<summary><b>paper-plugin.yml (minimal)</b></summary>

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.myplugin.MainPlugin
api-version: '1.21'
# The plugin will auto‑patch these if not present:
# loader: cc.modlabs.registration.DependencyLoader
# bootstrapper: cc.modlabs.registration.CommandBootstrapper
```
</details>

<details>
<summary><b>Command and Listener discovery</b></summary>

Note: Commands are auto-registered by the plugin. Listeners are discovered but you must register them manually in your plugin's enable phase:

```kotlin
class MyPlugin : org.bukkit.plugin.java.JavaPlugin() {
    override fun onEnable() {
        cc.modlabs.registration.RegisterManager.registerListeners(this)
    }
}
```

```kotlin
// Place these under your registration base package to be discovered
package com.example.myplugin.commands

import cc.modlabs.kpaper.command.CommandBuilder
import io.papermc.paper.command.brigadier.Commands

class HelloCommand : CommandBuilder {
    override val description = "Say hello"
    override val aliases = listOf("hi")

    override fun register() = Commands.literal("hello")
        .executes {
            it.source.sender.sendMessage("Hello from KPaper!")
            com.mojang.brigadier.Command.SINGLE_SUCCESS
        }
        .build()
}
```

```kotlin
package com.example.myplugin.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class JoinListener : Listener {
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        e.player.sendMessage("Welcome!")
    }
}
```
</details>

## 🧩 How it works

KPaperGradle wires a few build steps into your project:

- Generates sources in `build/generated/sources/kpaper/cc/modlabs/registration/`:
  - `CommandBootstrapper` — registers KPaper commands during the `COMMANDS` lifecycle
  - `RegisterManager` — scans your `registrationBasePackage` for `CommandBuilder` and `Listener` classes
  - `DependencyLoader` — resolves and attaches declared libraries at runtime via Paper's `MavenLibraryResolver`
- Registration behavior: Commands are auto-registered; listeners are discovered but must be registered manually via `RegisterManager.registerListeners(plugin)`
- Creates `.dependencies` under `build/generated-resources/` and copies it into `build/resources/main/`
- Patches `paper-plugin.yml` to add `loader` and `bootstrapper` if missing
- Adds `cc.modlabs:KPaper:<version>` to the `api` configuration
- Ensures Java/Kotlin compilation depends on the generation task and sees the generated sources

## 🔧 Configuration

The `kpaper` extension exposes the following properties:

- `javaVersion: Property<Int>` — Java toolchain and `--release` (default `21`)
- `registrationBasePackage: Property<String>` — base package to scan (default `"cc.modlabs"`)
- `deliver(vararg deps: String)` — declare runtime libraries, e.g. `deliver("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")`

Environment variables (for building this Gradle plugin itself):

- `KPAPER_VERSION` — pinned KPaper version embedded into the plugin (falls back to a timestamped default)
- `VERSION_OVERRIDE` — override the plugin’s published version
- `NEXUS_USER` / `NEXUS_PASS` — credentials for publishing to ModLabs Nexus

## 🏗 Minimal consumer project structure

```
my-plugin/
├─ build.gradle.kts
├─ src/main/resources/
│  └─ paper-plugin.yml
└─ src/main/kotlin/com/example/myplugin/
   ├─ commands/HelloCommand.kt
   └─ listeners/JoinListener.kt
```

## 🤝 Contributing

We welcome contributions! Fixes, features, and documentation improvements are all appreciated.

### Development Setup

```bash
git clone https://github.com/ModLabsCC/KPaperGradle.git
cd KPaperGradle
./gradlew build
```

### How to Contribute

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes following our coding standards
4. Add/adjust tests if applicable
5. Update documentation as needed
6. Commit (`git commit -m 'Add amazing feature'`)
7. Push (`git push origin feature/amazing-feature`)
8. Open a Pull Request

### Contribution Guidelines

- Follow Kotlin coding conventions
- KDoc for public APIs
- Keep commits focused and atomic
- Update docs for user-facing changes

## 📄 License

KPaperGradle is licensed under the **GPL-3.0 License**. See [LICENSE](LICENSE) for details.

## 🙏 Acknowledgments

- **[KPaper](https://github.com/ModLabsCC/KPaper)** — The companion library this plugin streamlines
- **[Paper](https://papermc.io/)** — For excellent modern Minecraft server software
- **[Kotlin](https://kotlinlang.org/)** — A delightful JVM language

---

<div align="center">

**Made with ❤️ by the ModLabs Team**

[Website](https://modlabs.cc) • [Discord](https://dc.modlabs.cc) • [GitHub](https://github.com/ModLabsCC)

</div>
