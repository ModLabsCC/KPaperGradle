package cc.modlabs.kpapergradle

import cc.modlabs.kpapergradle.internal.KPAPER_VERSION

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.net.URI

open class KPaperExtension(objects: ObjectFactory) {
    val deliverDependencies = mutableListOf<String>()
    val javaVersion: Property<Int> = objects.property(Int::class.java).convention(21)
    fun deliver(vararg deps: String) { deliverDependencies += deps }
}

class KPaperGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("kpaper", KPaperExtension::class.java, project.objects)

        project.repositories.maven {
            it.url = URI.create("https://nexus.modlabs.cc/repository/maven-mirrors/")
        }

        val kpaperCoords = "cc.modlabs:KPaper:$KPAPER_VERSION"
        project.dependencies.add("api", kpaperCoords)

        project.afterEvaluate {
            ext.deliverDependencies.forEach {
                project.dependencies.add("implementation", it)
            }
        }

        project.extensions.configure(JavaPluginExtension::class.java) { jext ->
            jext.toolchain.languageVersion.set(ext.javaVersion.map { JavaLanguageVersion.of(it) })
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            it.options.release.set(ext.javaVersion)
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
    }
}
