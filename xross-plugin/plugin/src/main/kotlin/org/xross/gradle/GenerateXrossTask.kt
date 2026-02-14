package org.xross.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

// --- 並列実行タスク ---
abstract class GenerateXrossTask
@Inject
constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val metadataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @TaskAction
    fun execute() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val jsonFiles = metadataDir.asFileTree.files.filter { it.extension == "json" }
        val queue = workerExecutor.noIsolation() // プロセス分離が必要なら classLoaderIsolation()
        jsonFiles.forEach { file ->
            queue.submit(GenerateAction::class.java) { params ->
                params.jsonFile.set(file)
                params.outputDir.set(outDir)
                params.packageName.set(packageName)
                params.metadataDir.set(metadataDir.get().asFile)
            }
        }
        queue.await()
    }
}
