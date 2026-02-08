package org.xross.generator

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import org.xross.structures.XrossDefinition
import java.io.File

// 収集用の Set は削除（メタデータに Opaque が含まれる前提）
object XrossGenerator {
    fun generate(meta: XrossDefinition, outputDir: File, targetPackage: String) {
        // メタデータの種類（kind）に応じて生成処理を完全に分離
        when (meta) {
            is XrossDefinition.Opaque -> {
                // Opaque の場合は専用のジェネレータに投げて終了
                OpaqueGenerator.generateSingle(meta, outputDir, targetPackage)
            }

            is XrossDefinition.Struct, is XrossDefinition.Enum -> {
                generateComplexType(meta, outputDir, targetPackage)
            }
        }
    }

    private fun generateComplexType(meta: XrossDefinition, outputDir: File, targetPackage: String) {
        val className = meta.name

        // 1. クラスの基本構造を決定
        val classBuilder = when (meta) {
            is XrossDefinition.Struct -> {
                TypeSpec.classBuilder(className).addSuperinterface(AutoCloseable::class)
            }

            is XrossDefinition.Enum -> {
                TypeSpec.classBuilder(className).addModifiers(KModifier.SEALED)
                    .addSuperinterface(AutoCloseable::class)
            }

            else -> throw IllegalArgumentException("Unsupported type")
        }

        // 2~5. 既存の生成ロジック (Structure, Companion, Method, Property)
        StructureGenerator.buildBase(classBuilder, meta)
        val companionBuilder = TypeSpec.companionObjectBuilder()
        CompanionGenerator.generateCompanions(companionBuilder, meta)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta)

        when (meta) {
            is XrossDefinition.Struct -> PropertyGenerator.generateFields(classBuilder, meta, targetPackage)
            is XrossDefinition.Enum -> EnumVariantGenerator.generateVariants(
                classBuilder, companionBuilder, meta, targetPackage
            )
        }

        // 6. 仕上げ
        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        writeToDisk(classBuilder.build(), targetPackage, className, outputDir)
    }

    private fun writeToDisk(typeSpec: TypeSpec, pkg: String, name: String, outputDir: File) {
        // パッケージ解決 (targetPackage と meta.packageName の結合は外部またはここで行う)
        val fileSpec = FileSpec.builder(pkg, name).addType(typeSpec).indent("    ").build()
        var content = fileSpec.toString()

        val redundantKeywords =
            listOf("class", "interface", "fun", "val", "var", "object", "sealed", "constructor", "companion")
        redundantKeywords.forEach { content = content.replace("public $it", it) }

        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$name.kt").writeText(content)
    }
}