package org.xross.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import javax.inject.Inject

/**
 * Gradle plugin for generating Kotlin bindings from Rust Xross metadata.
 */
@Suppress("unused")
class XrossPlugin
@Inject
constructor() : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("xross", XrossExtension::class.java)
        val outputDir = project.layout.buildDirectory.dir("generated/source/xross/main/kotlin")

        val generateXrossBindings = project.tasks.register("generateXrossBindings", GenerateXrossTask::class.java) { task ->
            val metadataDirStr = extension.metadataDir
            val metadataDir = File(metadataDirStr)
            task.metadataDir.set(metadataDir)
            task.outputDir.set(outputDir)
            task.packageName.set(extension.packageName)
        }

        project.afterEvaluate {
            project.extensions.findByType(SourceSetContainer::class.java)?.named("main") { ss ->
                ss.java.srcDir(generateXrossBindings)
            }
        }
    }
}
