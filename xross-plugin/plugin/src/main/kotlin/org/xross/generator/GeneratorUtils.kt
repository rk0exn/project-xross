package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.structures.XrossDefinition
import java.io.File

object GeneratorUtils {
    private val MEMORY_SEGMENT = ClassName("java.lang.foreign", "MemorySegment")
    private val ARENA = ClassName("java.lang.foreign", "Arena")
    fun isPureEnum(meta: XrossDefinition): Boolean =
        meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }

    fun getClassName(
        signature: String,
        basePackage: String,
    ): ClassName {
        val fqn =
            if (basePackage.isEmpty() || signature.startsWith(basePackage)) {
                signature
            } else {
                "$basePackage.$signature"
            }
        val lastDot = fqn.lastIndexOf('.')
        return if (lastDot == -1) {
            ClassName("", fqn)
        } else {
            ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
        }
    }

    /**
     * Kotlin においてデフォルト（省略可能）な public 修飾子を正規表現で一括削除する。
     */
    fun cleanupPublic(content: String): String {
        val keywords =
            listOf(
                "class",
                "interface",
                "fun",
                "val",
                "var",
                "object",
                "enum",
                "sealed",
                "open",
                "abstract",
                "constructor",
                "companion",
                "init",
                "data",
                "override",
                "lateinit",
                "inner",
            ).joinToString("|")

        val regex = Regex("""public\s+(?=$keywords)""")
        return content.replace(regex, "")
    }

    fun writeToDisk(
        fileSpec: FileSpec,
        outputDir: File,
    ) {
        val content = cleanupPublic(fileSpec.toString())
        val fileDir = outputDir.resolve(fileSpec.packageName.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("${fileSpec.name}.kt").writeText(content)
    }

    fun writeToDisk(
        typeSpec: TypeSpec,
        pkg: String,
        name: String,
        outputDir: File,
    ) {
        val fileSpec =
            FileSpec
                .builder(pkg, name)
                .addType(typeSpec)
                .indent("    ")
                .build()
        writeToDisk(fileSpec, outputDir)
    }

    fun buildOptimisticReadGetter(kType: TypeName, readCode: CodeBlock): FunSpec {
        return FunSpec.getterBuilder().addCode(
            CodeBlock.builder()
                .addStatement("var stamp = this.sl.tryOptimisticRead()")
                .addStatement("var res: %T", kType)
                .add("\n// Optimistic read\n")
                .add(readCode)
                .beginControlFlow("if (!this.sl.validate(stamp))")
                .addStatement("stamp = this.sl.readLock()")
                .beginControlFlow("try")
                .add("\n// Pessimistic read\n")
                .add(readCode)
                .nextControlFlow("finally")
                .addStatement("this.sl.unlockRead(stamp)")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return res")
                .build(),
        ).build()
    }

    fun addRustStringResolution(body: CodeBlock.Builder, call: Any, resultVar: String = "str", isAssignment: Boolean = false, shouldFree: Boolean = true) {
        val resRawName = if (call is String && call.endsWith("Raw")) call else "${resultVar}RawInternal"
        if (call is String && call == resRawName) {
            // Already cast or assigned
        } else {
            body.addStatement("val $resRawName = %L as %T", call, MEMORY_SEGMENT)
        }
        val prefix = if (isAssignment) "" else "val "
        body.addStatement(
            "$prefix$resultVar = if ($resRawName == %T.NULL) \"\" else $resRawName.reinterpret(%T.MAX_VALUE).getString(0)",
            MEMORY_SEGMENT,
            Long::class.asTypeName(),
        )
        if (shouldFree) {
            body.addStatement("if ($resRawName != %T.NULL) xrossFreeStringHandle.invokeExact($resRawName)", MEMORY_SEGMENT)
        }
    }

    fun getFactoryTripleType(basePackage: String): TypeName {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val arenaPair = Pair::class.asClassName().parameterizedBy(ARENA, ARENA.copy(nullable = true))
        return Triple::class.asClassName().parameterizedBy(MEMORY_SEGMENT, arenaPair, aliveFlagType)
    }

    fun addFactoryBody(
        body: CodeBlock.Builder,
        basePackage: String,
        handleCall: CodeBlock,
        structSizeExpr: CodeBlock,
        dropHandleExpr: CodeBlock,
    ) {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        body.addStatement("val newAutoArena = %T.ofAuto()", ARENA)
        body.addStatement("val newOwnerArena = %T.ofAuto()", ARENA)
        body.addStatement("val flag = %T(true)", aliveFlagType)
        body.addStatement("val resRaw = %L as %T", handleCall, MEMORY_SEGMENT)
        body.addStatement(
            "if (resRaw == %T.NULL) throw %T(%S)",
            MEMORY_SEGMENT,
            RuntimeException::class.asTypeName(),
            "Fail"
        )
        body.addStatement(
            "val res = resRaw.reinterpret(%L, newAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
            structSizeExpr,
            dropHandleExpr,
        )
    }

    fun buildFromPointerBase(
        name: String,
        returnType: TypeName,
        basePackage: String,
    ): FunSpec.Builder {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        return FunSpec.builder(name)
            .addParameter("ptr", MEMORY_SEGMENT)
            .addParameter("autoArena", ARENA)
            .addParameter(
                ParameterSpec.builder("confinedArena", ARENA.copy(nullable = true))
                    .defaultValue("null")
                    .build(),
            )
            .addParameter(
                ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true))
                    .defaultValue("null")
                    .build(),
            )
            .returns(returnType)
            .addModifiers(KModifier.INTERNAL)
    }
}
