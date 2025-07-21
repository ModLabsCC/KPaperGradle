package cc.modlabs.kpapergradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import java.io.File

open class KPaperDeliverExtension {
    val deliverDependencies = mutableListOf<String>()
    fun deliver(vararg deps: String) {
        deliverDependencies += deps
    }
}

class KPaperGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Extension for users to deliver more dependencies if needed
        val ext = project.extensions.create("deliver", KPaperDeliverExtension::class.java)

        // Always add KPaper
        val kpaperVersion = "2025.7.15.1527"
        val kpaperCoords = "cc.modlabs:KPaper:$kpaperVersion"
        project.dependencies.add("api", kpaperCoords)

        // Register and configure generation task
        val generateDepsTask = project.tasks.register("generateDependenciesFile")
        val generateDeps = generateDepsTask.get() // <-- GET THE REAL TASK!

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

        // Configure processResources task (if exists)
        project.tasks.matching { it.name == "processResources" }.all { task ->
            task.dependsOn(generateDeps)
            task.doLast {
                // Actually copy into resources
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