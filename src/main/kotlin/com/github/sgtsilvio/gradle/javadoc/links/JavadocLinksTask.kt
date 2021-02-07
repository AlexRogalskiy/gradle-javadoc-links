package com.github.sgtsilvio.gradle.javadoc.links

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.function.Function
import java.util.stream.Collectors
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
open class JavadocLinksTask @Inject constructor(private val javadocProvider: Provider<Javadoc>) : DefaultTask() {

    @InputFiles
    val projectLinksConfiguration: Configuration = project.configurations.create(name) { configuration ->
        configuration.isVisible = false
        configuration.isTransitive = false
        configuration.isCanBeResolved = true
        configuration.isCanBeConsumed = false
        configuration.attributes { attributes ->
            attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, Category.DOCUMENTATION)
            )
            attributes.attribute(
                DocsType.DOCS_TYPE_ATTRIBUTE,
                project.objects.named(DocsType::class.java, DocsType.JAVADOC)
            )
        }
    }

    private val links = LinkedList<ModuleVersionIdentifier>()

    @Internal
    var urlProvider = Function<ModuleVersionIdentifier, String> { moduleId ->
        "https://javadoc.io/doc/${moduleId.group}/${moduleId.name}/${moduleId.version}/"
    }

    init {
        useDependenciesOf("apiElements")
    }

    fun useDependenciesOf(configurationName: String) {
        projectLinksConfiguration.dependencies.clear()
        links.clear()

        val configuration = project.configurations.getByName(configurationName)
        val dependencySet = configuration.allDependencies.stream()
            .map { dependency -> Pair(dependency.group, dependency.name) }
            .collect(Collectors.toSet())

        val compileClasspath = project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
        compileClasspath.incoming.resolutionResult.root.dependencies.forEach { dependencyResult ->
            if (dependencyResult !is ResolvedDependencyResult) {
                throw GradleException("can not create javadoc link for unresolved dependency: $dependencyResult")
            }
            val selected = dependencyResult.selected
            val moduleVersionId = selected.moduleVersion
            if (moduleVersionId == null || !dependencySet.contains(Pair(moduleVersionId.group, moduleVersionId.name))) {
                return@forEach
            }
            when (val componentId = selected.id) {
                is ModuleComponentIdentifier -> links.add(moduleVersionId)
                is ProjectComponentIdentifier -> {
                    if (componentId.build.isCurrentBuild) {
                        projectLinksConfiguration.dependencies.add(
                            project.dependencies.project(mapOf("path" to componentId.projectPath))
                        )
                    } else {
                        projectLinksConfiguration.dependencies.add(
                            project.dependencies.create("${moduleVersionId.group}:${moduleVersionId.name}")
                        )
                    }
                }
            }
        }
    }

    @TaskAction
    fun run() {
        val javadoc = javadocProvider.get()
        val javadocOptions = javadoc.options as StandardJavadocDocletOptions
        val javaVersion = JavaVersion.current()
        val downloadAndLinkOffline = !javaVersion.isJava10Compatible

        javadocOptions.links(
            if (javaVersion.isJava11Compatible) {
                "https://docs.oracle.com/en/java/javase/${javaVersion.majorVersion}/docs/api/"
            } else {
                "https://docs.oracle.com/javase/${javaVersion.majorVersion}/docs/api/"
            }
        )

        fun getOfflineLocation(moduleVersionId: ModuleVersionIdentifier): String =
            "${temporaryDir}/${moduleVersionId.group}/${moduleVersionId.name}/${moduleVersionId.version}"

        links.forEach { moduleVersionId ->
            val url = urlProvider.apply(moduleVersionId)
            if (!downloadAndLinkOffline) {
                javadocOptions.links(url)
            } else {
                val offlineLocation = getOfflineLocation(moduleVersionId)
                javadocOptions.linksOffline(url, offlineLocation)

                val packageListFile = File(offlineLocation, "package-list")
                if (!packageListFile.exists()) {
                    packageListFile.parentFile.mkdirs()
                    try {
                        URL("${url}package-list").openStream().use { input ->
                            packageListFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (ignored: IOException) {
                        try {
                            URL("${url}element-list").openStream().use { input ->
                                packageListFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (ignored: IOException) {
                            logger.warn("Neither package-list nor element-list found for {}", url)
                        }
                    }
                }
            }
        }

        projectLinksConfiguration.resolvedConfiguration.resolvedArtifacts.forEach { resolvedArtifact ->
            val moduleVersionId = resolvedArtifact.moduleVersion.id
            val url = urlProvider.apply(moduleVersionId)
            val offlineLocation = getOfflineLocation(moduleVersionId)
            javadocOptions.linksOffline(url, offlineLocation)

            project.copy { copySpec ->
                copySpec.from(project.zipTree(resolvedArtifact.file)) { zipCopySpec ->
                    zipCopySpec.include("package-list")
                    zipCopySpec.include("element-list")
                }
                copySpec.into(offlineLocation)
            }
        }
    }
}