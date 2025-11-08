import java.util.*

plugins {
    id("java-gradle-plugin")
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
    id("maven-publish")
}

group = "cc.modlabs"
version = System.getenv("VERSION_OVERRIDE") ?: Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
    "${get(Calendar.YEAR)}.${get(Calendar.MONTH) + 1}.${get(Calendar.DAY_OF_MONTH)}.${String.format("%02d%02d", get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))}"
}

repositories {
    maven("https://nexus.modlabs.cc/repository/maven-mirrors/")
}

gradlePlugin {
    plugins {
        create("kpaperGradle") {
            id = "cc.modlabs.kpaper-gradle"
            implementationClass = "cc.modlabs.kpapergradle.KPaperGradlePlugin"
        }
    }
}

val kpaperVersion = System.getenv("KPAPER_VERSION") ?: "2025.7.15.1527"

tasks.register("generateKPaperVersion") {
    val outputDir = file("src/main/kotlin/cc/modlabs/kpapergradle/internal/")
    inputs.property("kpaperVersion", kpaperVersion)
    outputs.dir(outputDir)
    doLast {
        outputDir.mkdirs()
        val file = File(outputDir, "KPaperVersion.kt")
        file.writeText("""
            package cc.modlabs.kpapergradle.internal
            const val KPAPER_VERSION = "$kpaperVersion"
        """.trimIndent())
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("generateKPaperVersion")
}

publishing {
    repositories {
        maven {
            name = "ModLabs"
            url = uri("https://nexus.modlabs.cc/repository/maven-public/")
            credentials {
                username = System.getenv("NEXUS_USER")
                password = System.getenv("NEXUS_PASS")
            }
        }
        mavenLocal()
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("KPaperGradle")
                description.set("Gradle plugin for automatically integrating and delivering KPaper in PaperMC plugin projects.")
                url.set("https://github.com/ModLabsCC/KPaperGradle")
                licenses {
                    license {
                        name.set("GPL-3.0")
                        url.set("https://github.com/ModLabsCC/KPaperGradle/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("ModLabsCC")
                        name.set("ModLabsCC")
                        email.set("contact@modlabs.cc")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/ModLabsCC/KPaperGradle.git")
                    developerConnection.set("scm:git:git@github.com:ModLabsCC/KPaperGradle.git")
                    url.set("https://github.com/ModLabsCC/KPaperGradle")
                }
            }
        }
    }
}
