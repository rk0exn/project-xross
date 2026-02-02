package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.XrossClass
import org.xross.XrossMethodType
import java.io.File

object XrossGenerator {
    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val className = meta.structName
        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(AutoCloseable::class)
        // 1. 基礎構造 (StructureGenerator)
        StructureGenerator.buildBase(classBuilder, meta)
        // 2. ハンドル定義 (HandleGenerator)
        val companionBuilder = TypeSpec.companionObjectBuilder()
        HandleGenerator.generateHandles(companionBuilder, meta)
        // 3. メソッド (MethodGenerator)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta)

        // 4. プロパティ (PropertyGenerator)
        PropertyGenerator.generateFields(classBuilder, meta)

        // 5. 仕上げ (Deallocator, close等)
        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        // FileSpecの構築
        val fileSpec = FileSpec.builder(targetPackage, className)
            .indent("    ")
            .apply {
                // 必要なJava FFI系インポートを限定
                addImport("java.lang.foreign", "MemorySegment")

                // メソッドがある場合のみ追加
                if (meta.methods.isNotEmpty()) {
                    addImport("java.lang.foreign", "FunctionDescriptor", "Linker", "SymbolLookup")
                    if (meta.methods.any { it.args.any { a -> a.ty.kotlinType.toString().contains("String") } }) {
                        addImport("java.lang.foreign", "Arena")
                    }
                }

                // インスタンスメソッドがある場合のみロックのインポート
                if (meta.methods.any { it.methodType != XrossMethodType.Static }) {
                    addImport("kotlin.concurrent", "withLock")
                }
            }
            .addType(classBuilder.build())
            .build()

        // 文字列として書き出し、不要な修飾子を置換
        val rawContent = fileSpec.toString()
        val cleanedContent = rawContent
            .replace("public class", "class")
            .replace("public fun", "fun")
            .replace("public constructor", "constructor")
            .replace("public val", "val")
            .replace("public companion object", "companion object")
            // KotlinPoetが時折生成する冗長なUnit戻り値を整理
            .replace(": Unit {", " {")

        // ファイル書き出し
        val fileDir = outputDir.resolve(targetPackage.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$className.kt").writeText(cleanedContent)
    }
}
