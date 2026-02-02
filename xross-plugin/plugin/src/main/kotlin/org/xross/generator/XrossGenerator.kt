package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethodType
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
        val cleanedContent = rawContent.let { content ->
            val publicKeywords = listOf("class", "fun", "constructor", "val", "var", "companion object", "typealias")
            val stage1 = publicKeywords.fold(content) { acc, keyword ->
                acc.replace(Regex("""\bpublic\s+$keyword\b"""), keyword)
            }
            // 2. 冗長な Unit 戻り値の削除
            // メソッド定義の末尾にある ": Unit" を削除
            val stage2 = stage1.replace(Regex(""":\s*Unit\s*\{"""), " {")
            // 3. (オプション) ゲッター/セッターの public も整理する場合
            stage2.replace(Regex("""\bpublic\s+get\b\(\)"""), "get()")
                .replace(Regex("""\bpublic\s+set\b"""), "set")
        }
        // ファイル書き出し
        val fileDir = outputDir.resolve(targetPackage.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$className.kt").writeText(cleanedContent)
    }
}
