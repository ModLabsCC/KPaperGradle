package cc.modlabs.kpapergradle

import cc.modlabs.kpapergradle.internal.KPAPER_VERSION

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.SourceTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.net.URI

open class KPaperExtension(objects: ObjectFactory) {
    val deliverDependencies = mutableListOf<String>()
    val javaVersion: Property<Int> = objects.property(Int::class.java).convention(21)
    // Base package used by RegisterManager to scan for commands/listeners
    val registrationBasePackage: Property<String> = objects.property(String::class.java).convention("cc.modlabs")

    // Custom repositories for the runtime dependency loader (MavenLibraryResolver)
    internal val customRepositories = mutableListOf<Pair<String, String>>() // id to url

    fun deliver(vararg deps: String) { deliverDependencies += deps }

    // DSL: repository("https://repo1.maven.org/maven2/")
    fun repository(url: String) {
        val host = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }
        val id = host.replace(Regex("[^a-zA-Z0-9-_]"), "-")
        customRepositories += id to url
    }

    // DSL: repository("myRepo", "https://repo.example.com/maven/")
    fun repository(id: String, url: String) {
        customRepositories += id to url
    }
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
            val buildDir = project.layout.buildDirectory.asFile.get()
            val genResDir = File(buildDir, "generated-resources")
            genResDir.mkdirs()
            val depFile = File(genResDir, ".dependencies")
            depFile.writeText(delivered.joinToString("\n"))

            // Write repositories file for the runtime dependency loader
            val reposFile = File(genResDir, ".repositories")
            if (ext.customRepositories.isNotEmpty()) {
                val lines = ext.customRepositories.map { (id, url) -> "$id $url" }
                reposFile.writeText(lines.joinToString("\n"))
            } else {
                if (reposFile.exists()) reposFile.delete()
            }
        }

        project.tasks.matching { it.name == "processResources" }.all { task ->
            task.dependsOn(generateDeps)
            task.doLast {
                val buildDir = project.layout.buildDirectory.asFile.get()
                val depFile = File(buildDir, "generated-resources/.dependencies")
                val reposFile = File(buildDir, "generated-resources/.repositories")
                val resourcesDir = File(buildDir, "resources/main")
                resourcesDir.mkdirs()
                if (depFile.exists()) {
                    depFile.copyTo(File(resourcesDir, ".dependencies"), overwrite = true)
                }
                if (reposFile.exists()) {
                    reposFile.copyTo(File(resourcesDir, ".repositories"), overwrite = true)
                }

                // Patch paper-plugin.yml to add loader/bootstrapper if they are not set
                val paperPlugin = File(resourcesDir, "paper-plugin.yml")
                if (paperPlugin.exists()) {
                    var content = paperPlugin.readText()
                    var modified = false
                    val hasLoader = Regex("(?m)^\\s*loader\\s*:").containsMatchIn(content)
                    val hasBootstrapper = Regex("(?m)^\\s*bootstrapper\\s*:").containsMatchIn(content)

                    val linesToAppend = mutableListOf<String>()
                    if (!hasLoader) {
                        linesToAppend += "loader: cc.modlabs.registration.DependencyLoader"
                    }
                    if (!hasBootstrapper) {
                        linesToAppend += "bootstrapper: cc.modlabs.registration.CommandBootstrapper"
                    }

                    if (linesToAppend.isNotEmpty()) {
                        val sep = if (content.endsWith("\n")) "" else "\n"
                        content += sep + linesToAppend.joinToString("\n") + "\n"
                        modified = true
                    }

                    if (modified) {
                        paperPlugin.writeText(content)
                    }
                }
            }
        }

        // Generate required registration/loader classes so they're present on the plugin classpath
        val genSourcesDir = File(project.layout.buildDirectory.asFile.get(), "generated/sources/kpaper")
        val generateRegistration = project.tasks.register("generateRegistrationSources") { t ->
            t.group = "build"
            t.description = "Generates required registration/bootstrap/loader classes into the classpath"
            // Track inputs to allow incremental builds
            t.inputs.property("registrationBasePackage", ext.registrationBasePackage)
            t.doLast {
                val basePkg = File(genSourcesDir, "cc/modlabs/registration")
                basePkg.mkdirs()

                val scanBase = ext.registrationBasePackage.get()

                // CommandBootstrapper (Kotlin)
                val commandBootstrapper = File(basePkg, "CommandBootstrapper.kt")
                commandBootstrapper.writeText(
                    """
                    package cc.modlabs.registration
                    
                    import io.papermc.paper.plugin.bootstrap.BootstrapContext
                    import io.papermc.paper.plugin.bootstrap.PluginBootstrap
                    import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
                    
                    class CommandBootstrapper : PluginBootstrap {
                        override fun bootstrap(context: BootstrapContext) {
                            val manager = context.lifecycleManager
                            manager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                                RegisterManager.registerCommands(event.registrar())
                            }
                        }
                    }
                    """.trimIndent()
                )

                // RegisterManager (Kotlin)
                val registerManager = File(basePkg, "RegisterManager.kt")
                registerManager.writeText(
                    """
                    package cc.modlabs.registration
                    
                    import cc.modlabs.kpaper.command.CommandBuilder
                    import com.google.common.reflect.ClassPath
                    import dev.fruxz.ascend.extension.logging.getFactoryLogger
                    import io.papermc.paper.command.brigadier.Commands
                    import org.bukkit.Bukkit
                    import org.bukkit.event.Listener
                    import org.bukkit.plugin.Plugin
                    import kotlin.reflect.KClass
                    import kotlin.reflect.full.primaryConstructor
                    
                    object RegisterManager {
                    
                        private val logger = getFactoryLogger(RegisterManager::class)
                        private const val PACKAGE_NAME = "${scanBase.replace("\"","\\\"")}"
                    
                        private fun <T : Any, E : Any> E.loadClassesInPackage(
                            packageName: String,
                            clazzType: KClass<T>
                        ): List<KClass<out T>> {
                            try {
                                val classLoader = this.javaClass.classLoader
                                val allClasses = ClassPath.from(classLoader).allClasses
                                val classes = mutableListOf<KClass<out T>>()
                                for (classInfo in allClasses) {
                                    if (!classInfo.name.startsWith(PACKAGE_NAME)) continue
                                    if (classInfo.packageName.startsWith(packageName) && !classInfo.name.contains('$')) {
                                        try {
                                            val loadedClass = classInfo.load().kotlin
                                            if (clazzType.isInstance(loadedClass.javaObjectType.getDeclaredConstructor().newInstance())) {
                                                @Suppress("UNCHECKED_CAST")
                                                classes.add(loadedClass as KClass<out T>)
                                            }
                                        } catch (_: Exception) {
                                            // Ignore, as this is not a class we need to load
                                        }
                                    }
                                }
                                return classes
                            } catch (exception: Exception) {
                                logger.error("Failed to load classes", exception)
                                return emptyList()
                            }
                        }
                    
                        @JvmStatic
                        fun registerCommands(commands: Commands) {
                            val commandClasses = loadClassesInPackage("${'$'}PACKAGE_NAME.commands", CommandBuilder::class)
                    
                            commandClasses.forEach {
                                val command = it.primaryConstructor?.call() as CommandBuilder
                    
                                commands.register(
                                    command.register(),
                                    command.description,
                                    command.aliases
                                )
                    
                                logger.info("Command ${'$'}{it.simpleName} registered")
                            }
                    
                            logger.info("Registered ${'$'}{commandClasses.size} minecraft commands")
                        }
                    
                        @JvmStatic
                        fun registerListeners(plugin: Plugin) {
                            val listenerClasses = loadClassesInPackage("${'$'}PACKAGE_NAME.listeners", Listener::class)
                    
                            var amountListeners = 0
                            listenerClasses.forEach {
                                try {
                                    val listener = it.primaryConstructor?.call() as Listener
                                    Bukkit.getPluginManager().registerEvents(listener, plugin)
                                    amountListeners++
                                    logger.info("Registered listener: ${'$'}{it.simpleName}")
                                } catch (e: Exception) {
                                    logger.error("Failed to register listener: ${'$'}{it.simpleName}", e)
                                }
                            }
                            if (amountListeners == 0) return
                            plugin.logger.info("Registered ${'$'}amountListeners listeners")
                        }
                    }
                    """.trimIndent()
                )

                // DependencyLoader (Java) - must not rely on Kotlin runtime
                val dependencyLoader = File(basePkg, "DependencyLoader.java")
                dependencyLoader.writeText(
                    """
                    package cc.modlabs.registration;
                    
                    import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
                    import io.papermc.paper.plugin.loader.PluginLoader;
                    import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
                    import org.eclipse.aether.artifact.DefaultArtifact;
                    import org.eclipse.aether.graph.Dependency;
                    import org.eclipse.aether.repository.RemoteRepository;
                    
                    import java.io.BufferedReader;
                    import java.io.InputStreamReader;
                    import java.util.Objects;
                    import java.util.logging.Level;
                    import java.util.logging.Logger;
                    
                    @SuppressWarnings("UnstableApiUsage")
                    public class DependencyLoader implements PluginLoader {
                    
                        private static final Logger LOGGER = Logger.getLogger(DependencyLoader.class.getName());
                    
                        @Override
                        public void classloader(PluginClasspathBuilder classpathBuilder) {
                            MavenLibraryResolver maven = new MavenLibraryResolver();
                    
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(".dependencies"))))) {
                                reader.lines().forEach(dependency -> {
                                    LOGGER.log(Level.INFO, "Adding dependency: " + dependency);
                                    maven.addDependency(new Dependency(new DefaultArtifact(dependency), null));
                                });
                    
                                // Add default ModLabs mirror
                                maven.addRepository(new RemoteRepository.Builder("modlabs", "default", "https://nexus.modlabs.cc/repository/maven-mirrors/").build());

                                // Add custom repositories from optional .repositories resource
                                try {
                                    java.io.InputStream reposStream = getClass().getClassLoader().getResourceAsStream(".repositories");
                                    if (reposStream != null) {
                                        try (BufferedReader r = new BufferedReader(new InputStreamReader(reposStream))) {
                                            r.lines().forEach(line -> {
                                                String trimmed = line.trim();
                                                if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
                                                String id;
                                                String url;
                                                String[] parts = trimmed.split("\\s+", 2);
                                                if (parts.length == 2) {
                                                    id = parts[0];
                                                    url = parts[1];
                                                } else {
                                                    url = trimmed;
                                                    try {
                                                        java.net.URI u = java.net.URI.create(url);
                                                        String host = u.getHost();
                                                        id = host != null ? host.replaceAll("[^a-zA-Z0-9-_]", "-") : Integer.toString(url.hashCode());
                                                    } catch (Exception ex) {
                                                        id = Integer.toString(url.hashCode());
                                                    }
                                                }
                                                try {
                                                    maven.addRepository(new RemoteRepository.Builder(id, "default", url).build());
                                                } catch (Exception ex) {
                                                    LOGGER.log(Level.WARNING, "Failed to add repository: " + trimmed, ex);
                                                }
                                            });
                                        }
                                    }
                                } catch (Exception ex) {
                                    LOGGER.log(Level.WARNING, "Failed to read .repositories", ex);
                                }
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, "Failed to load dependencies", e);
                            }
                    
                            classpathBuilder.addLibrary(maven);
                        }
                    }
                    """.trimIndent()
                )
            }
        }.get()

        // Add generated dir to main source set so classes are compiled into the plugin jar
        val sourceSets = project.extensions.findByType(SourceSetContainer::class.java)
        sourceSets?.getByName("main")?.java?.srcDir(genSourcesDir)

        // Ensure Kotlin and Java compilation depend on generation and see the sources
        project.tasks.withType(JavaCompile::class.java).configureEach { it.dependsOn(generateRegistration) }
        // Make Kotlin compilation depend on the generation and include the generated sources explicitly
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            // Ensure generation runs before Kotlin compilation on the very first build too
            project.tasks.named("compileKotlin").configure { task ->
                task.dependsOn(generateRegistration)
                if (task is SourceTask) {
                    task.source(genSourcesDir)
                }
            }
        }
        // Fallback: for any additional Kotlin compile tasks (multi-source set), wire them too
        project.tasks.withType(SourceTask::class.java).configureEach { task ->
            if (task.name.startsWith("compileKotlin")) {
                task.dependsOn(generateRegistration)
                task.source(genSourcesDir)
            }
        }
    }
}
