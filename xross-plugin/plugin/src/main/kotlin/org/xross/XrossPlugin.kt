package org.xross

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import javax.inject.Inject

class XrossPlugin @Inject constructor() : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("xross", XrossExtension::class.java)
        val outputDir = project.layout.buildDirectory.dir("generated/source/xross/main/kotlin")

        project.afterEvaluate {
            project.extensions.findByType(SourceSetContainer::class.java)?.named("main") { ss ->
                ss.java.srcDir(outputDir)
            }
        }

        project.tasks.register("generateXrossBindings", GenerateXrossTask::class.java) { task ->
            val metadataDir = project.file(extension.rustProjectDir).resolve("target/xross")
            task.metadataDir.set(metadataDir)
            task.outputDir.set(outputDir)
            task.packageName.set(extension.packageName)
        }
    }
}
