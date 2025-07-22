package cc.modlabs.kpapergradle

import cc.modlabs.kpapergradle.internal.KPAPER_VERSION

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.internal.impldep.org.apache.http.client.methods.RequestBuilder.options
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

open class KPaperExtension {
    val deliverDependencies = mutableListOf<String>()
    fun deliver(vararg deps: String) {
        deliverDependencies += deps
    }
    var javaVersion: Int = 21
}

class KPaperGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("kpaper", KPaperExtension::class.java)

        val kpaperCoords = "cc.modlabs:KPaper:$KPAPER_VERSION"
        project.dependencies.add("api", kpaperCoords)

        ext.deliverDependencies.forEach {
            project.dependencies.add("implementation", it)
        }

        project.extensions.configure(JavaPluginExtension::class.java) { javaExt ->
            javaExt.toolchain.languageVersion.set(JavaLanguageVersion.of(ext.javaVersion))
        }

        val generateDepsTask = project.tasks.register("generateDependenciesFile")
        val generateDeps = generateDepsTask.get()

        generateDeps.group = "build"
        generateDeps.description = "Generates .dependencies file for delivery/compliance"
        generateDeps.doLast {
            val delivered = mutableSetOf<String>()
            project.configurations
                .matching { it.isCanBeResolved }
                .forEach { config ->
                    config.resolvedConfiguration
                        .firstLevelModuleDependencies
                        .forEach { dep ->
                            if (dep.moduleGroup == "cc.modlabs" && dep.moduleName == "KPaper") {
                                delivered.add("${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}")
                            }
                        }
                }
            delivered += ext.deliverDependencies
            val depFile = File(project.layout.buildDirectory.asFile.get(), "generated-resources/.dependencies")
            depFile.parentFile.mkdirs()
            depFile.writeText(delivered.joinToString("\n"))
        }

        project.tasks.matching { it.name == "processResources" }.all { task ->
            task.dependsOn(generateDeps)
            task.doLast {
                val depFile = File(project.layout.buildDirectory.asFile.get(), "generated-resources/.dependencies")
                val resourcesDir = File(project.layout.buildDirectory.asFile.get(), "resources/main")
                if (depFile.exists()) {
                    resourcesDir.mkdirs()
                    depFile.copyTo(File(resourcesDir, ".dependencies"), overwrite = true)
                }
            }
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            it.options.release.set(ext.javaVersion)
        }
    }
}
