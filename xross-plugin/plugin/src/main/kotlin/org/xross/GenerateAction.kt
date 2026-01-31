package org.xross

import kotlinx.serialization.json.Json
import org.gradle.workers.WorkAction
import kotlin.io.readText

abstract class GenerateAction : WorkAction<GenerateParameters> {
    private val json = Json { ignoreUnknownKeys = true }

    override fun execute() {
        val file = parameters.jsonFile.get()
        val meta = json.decodeFromString<XrossClass>(file.readText())
        val targetPackage = parameters.packageName.get().ifBlank { meta.packageName }

        XrossGenerator.generate(
            meta,
            parameters.outputDir.get().asFile,
            parameters.crateName.get(),
            targetPackage
        )
    }
}
