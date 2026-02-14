package org.xross.gradle

import kotlinx.serialization.json.Json
import org.gradle.workers.WorkAction
import org.xross.generator.TypeResolver
import org.xross.generator.XrossGenerator
import org.xross.structures.XrossDefinition

abstract class GenerateAction : WorkAction<GenerateParameters> {
    private val json = Json { ignoreUnknownKeys = true }

    override fun execute() {
        val file = parameters.jsonFile.get()
        val fileText = file.readText()
        val meta = json.decodeFromString<XrossDefinition>(fileText)
        // 1. ベースパッケージ (org.example)
        val basePackage = parameters.packageName.get()

        // 2. メタデータのパッケージ (test.test2)
        val subPackage = meta.packageName

        // 3. フルパッケージ名を生成 (org.example.test.test2)
        val fullPackage =
            if (subPackage.isBlank()) {
                basePackage
            } else {
                "$basePackage.$subPackage"
            }

        // 4. 【重要】ディレクトリは「パッケージ階層を含めないベース」を渡す
        // Generator側が内部で fullPackage.replace('.', '/') を実行している前提です
        val outputBaseDir = parameters.outputDir.get().asFile
        val resolver = TypeResolver(parameters.metadataDir.get())
        XrossGenerator.generate(
            meta,
            outputBaseDir, // ここで掘り進めない
            fullPackage,
            resolver,
        )
    }
}
