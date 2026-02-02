package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethodType
import org.xross.structures.XrossThreadSafety
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

        // 5. 仕上げ
        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        // FileSpecの構築
        val fileSpec = FileSpec.builder(targetPackage, className)
            .indent("    ")
            .apply {
                // 基本の FFI
                addImport("java.lang.foreign", "MemorySegment")

                // メソッドが存在する場合
                if (meta.methods.isNotEmpty()) {
                    addImport("java.lang.foreign", "FunctionDescriptor", "Linker", "SymbolLookup")
                    // 文字列またはスライスを扱う場合は Arena が必要
                    if (meta.methods.any { m -> m.args.any { a -> a.ty.kotlinType.toString().contains("String") } }) {
                        addImport("java.lang.foreign", "Arena")
                    }
                }

                // Atomic 操作がある場合、VarHandle と ValueLayout をインポート
                if (meta.fields.any { it.safety == XrossThreadSafety.Atomic }) {
                    addImport("java.lang.invoke", "VarHandle")
                }

                // ロックが必要な場合 (safety = Lock のメソッドやフィールドが一つでもある場合)
                val needsLock = meta.fields.any { it.safety == XrossThreadSafety.Lock } ||
                        meta.methods.any { it.safety == XrossThreadSafety.Lock && it.methodType != XrossMethodType.Static }
                if (needsLock) {
                    addImport("kotlin.concurrent", "withLock")
                }
            }
            .addType(classBuilder.build())
            .build()

        // 置換ロジックの実行
        val cleanedContent = cleanKotlinCode(fileSpec.toString())

        // ファイル書き出し
        val fileDir = outputDir.resolve(targetPackage.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$className.kt").writeText(cleanedContent)
    }

    private fun cleanKotlinCode(rawContent: String): String {
        return rawContent.let { content ->
            // 1. 公開修飾子の整理 (単語境界 \b を利用して安全に置換)
            val publicKeywords = listOf("class", "fun", "constructor", "val", "var", "companion object", "typealias")
            val stage1 = publicKeywords.fold(content) { acc, keyword ->
                acc.replace(Regex("""\bpublic\s+$keyword\b"""), keyword)
            }
            // 2. 冗長な Unit 戻り値の削除
            val stage2 = stage1.replace(Regex(""":\s*Unit\s*\{"""), " {")

            // 3. ゲッター/セッターの public 整理
            stage2.replace(Regex("""\bpublic\s+get\b\(\)"""), "get()")
                .replace(Regex("""\bpublic\s+set\b"""), "set")
        }
    }
}
