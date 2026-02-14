package org.xross.generator.util

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.io.File

/**
 * Utility functions for code generation.
 */
object GeneratorUtils {
    private val MEMORY_SEGMENT = ClassName("java.lang.foreign", "MemorySegment")
    private val ARENA = ClassName("java.lang.foreign", "Arena")

    /**
     * Returns true if the definition is a "pure" enum (one without fields in its variants).
     */
    fun isPureEnum(meta: XrossDefinition): Boolean = meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }

    /**
     * Resolves the [ClassName] for a given signature.
     */
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
     * Removes redundant 'public' modifiers from Kotlin code string.
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

    /**
     * Writes a [FileSpec] to disk, applying public modifier cleanup.
     */
    fun writeToDisk(
        fileSpec: FileSpec,
        outputDir: File,
    ) {
        val content = cleanupPublic(fileSpec.toString())
        val fileDir = outputDir.resolve(fileSpec.packageName.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("${fileSpec.name}.kt").writeText(content)
    }

    /**
     * Helper to write a [TypeSpec] to disk.
     */
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

    /**
     * Builds a getter with optimistic read locking using [java.util.concurrent.locks.StampedLock].
     * Optionally wraps with XrossAsyncLock read lock.
     */
    fun buildFullGetter(kType: TypeName, readCode: CodeBlock, useAsyncLock: Boolean = true): FunSpec {
        val optimisticReadCode = CodeBlock.builder()
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
            .build()

        return if (useAsyncLock) {
            FunSpec.getterBuilder()
                .addStatement("this.al.lockReadBlocking()")
                .beginControlFlow("try")
                .addCode(optimisticReadCode)
                .nextControlFlow("finally")
                .addStatement("this.al.unlockReadBlocking()")
                .endControlFlow()
                .build()
        } else {
            FunSpec.getterBuilder().addCode(optimisticReadCode).build()
        }
    }

    /**
     * Builds a setter with appropriate locking based on thread safety.
     * Optionally wraps with XrossAsyncLock write lock.
     */
    fun buildFullSetter(safety: XrossThreadSafety, kType: TypeName, writeCode: CodeBlock, useAsyncLock: Boolean = true): FunSpec {
        val alLock = if (useAsyncLock) {
            """
            this.al.lockWriteBlocking()
            try {
                %L
            } finally { this.al.unlockWriteBlocking() }
            """.trimIndent()
        } else {
            "%L"
        }

        val lockPattern = when (safety) {
            XrossThreadSafety.Immutable -> {
                """
                this.fl.lock()
                try {
                    $alLock
                } finally { this.fl.unlock() }
                """.trimIndent()
            }
            XrossThreadSafety.Unsafe -> alLock
            else -> {
                """
                val stamp = this.sl.writeLock()
                try {
                    $alLock
                } finally { this.sl.unlockWrite(stamp) }
                """.trimIndent()
            }
        }

        return FunSpec.setterBuilder()
            .addParameter("v", kType)
            .addCode(lockPattern, writeCode)
            .build()
    }

    /**
     * Resolves the Kotlin return type for a given XrossType.
     */
    fun resolveReturnType(type: XrossType, basePackage: String): TypeName = when (type) {
        is XrossType.Void -> UNIT
        is XrossType.RustString -> String::class.asTypeName()
        is XrossType.Object -> getClassName(type.signature, basePackage)
        is XrossType.Optional -> resolveReturnType(type.inner, basePackage).copy(nullable = true)
        is XrossType.Result -> ClassName("kotlin", "Result").parameterizedBy(resolveReturnType(type.ok, basePackage))
        else -> type.kotlinType
    }

    /**
     * Compares the target type with the self type and returns appropriate expressions for size, drop, and fromPointer.
     */
    fun compareExprs(
        targetTypeName: TypeName,
        selfType: ClassName,
        dropHandleName: String = "dropHandle",
    ): Triple<CodeBlock, CodeBlock, CodeBlock> {
        val isSelf = targetTypeName.copy(nullable = false) == selfType
        val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", targetTypeName)
        val dropExpr = if (isSelf) CodeBlock.of(dropHandleName) else CodeBlock.of("%T.dropHandle", targetTypeName)
        val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", targetTypeName)
        return Triple(sizeExpr, dropExpr, fromPointerExpr)
    }

    /**
     * Returns the type for the factory method return value.
     */
    fun getFactoryTripleType(basePackage: String): TypeName {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val arenaPair = Pair::class.asClassName().parameterizedBy(ARENA, ARENA.copy(nullable = true))
        return Triple::class.asClassName().parameterizedBy(MEMORY_SEGMENT, arenaPair, aliveFlagType)
    }

    /**
     * Returns an allocation expression based on the XrossType.
     */
    fun generateAllocMsg(ty: XrossType, valueName: String): CodeBlock = when (ty) {
        is XrossType.Object -> CodeBlock.of("$valueName.segment")
        is XrossType.RustString -> CodeBlock.of("arena.allocateFrom($valueName)")
        is XrossType.F32 -> CodeBlock.of("MemorySegment.ofAddress(%L.toRawBits().toLong())", valueName)
        is XrossType.F64 -> CodeBlock.of("MemorySegment.ofAddress(%L.toRawBits())", valueName)
        is XrossType.Bool -> CodeBlock.of("MemorySegment.ofAddress(if (%L) 1L else 0L)", valueName)
        else -> {
            // Integer types <= 8 bytes
            CodeBlock.of("MemorySegment.ofAddress(%L.toLong())", valueName)
        }
    }

    /**
     * Adds common internal constructor/factory parameters to a FunSpec builder.
     */
    fun FunSpec.Builder.addInternalParameters(
        aliveFlagType: TypeName,
        firstParamName: String = "ptr",
    ): FunSpec.Builder = this.addParameter(firstParamName, MEMORY_SEGMENT)
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

    /**
     * Builds the base for a 'fromPointer' method.
     */
    fun buildFromPointerBase(
        name: String,
        returnType: TypeName,
        basePackage: String,
    ): FunSpec.Builder {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        return FunSpec.builder(name)
            .addInternalParameters(aliveFlagType, "ptr")
            .returns(returnType)
            .addModifiers(KModifier.INTERNAL)
    }

    /**
     * Adds a backing property for caching object fields if necessary.
     */
    fun addBackingPropertyIfNeeded(
        builder: TypeSpec.Builder,
        field: org.xross.structures.XrossField,
        baseName: String,
        kType: TypeName,
    ): String? {
        if (field.ty is XrossType.Object) {
            val backingFieldName = "_$baseName"
            val weakRefType = ClassName("java.lang.ref", "WeakReference").parameterizedBy(kType)
            val backingProp =
                PropertySpec.builder(backingFieldName, weakRefType.copy(nullable = true))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("null")
                    .build()
            builder.addProperty(backingProp)
            return backingFieldName
        }
        return null
    }

    /**
     * Builds the internal constructor for enum variants or inherited structures.
     */
    fun buildRawInitializer(
        builder: TypeSpec.Builder,
        aliveFlagType: ClassName,
    ) {
        builder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addInternalParameters(aliveFlagType, "raw")
                .build(),
        )
        builder.addSuperclassConstructorParameter("raw")
        builder.addSuperclassConstructorParameter("autoArena")
        builder.addSuperclassConstructorParameter("confinedArena")
        builder.addSuperclassConstructorParameter("sharedFlag")
    }

    /**
     * Adds an internal constructor that takes a factory result (Triple) and calls 'this' constructor.
     */
    fun addInternalConstructor(typeBuilder: TypeSpec.Builder, tripleType: TypeName) {
        typeBuilder.addFunction(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("p", tripleType)
                .callThisConstructor(
                    CodeBlock.of("p.first"),
                    CodeBlock.of("p.second.first"),
                    CodeBlock.of("p.second.second"),
                    CodeBlock.of("p.third"),
                )
                .build(),
        )
    }
}
