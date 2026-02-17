package org.xross.generator.util

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
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
     * Returns true if the definition requires locking logic.
     */
    fun needsLocks(meta: XrossDefinition): Boolean = when (meta) {
        is XrossDefinition.Struct -> meta.fields.any { it.safety != XrossThreadSafety.Direct && it.safety != XrossThreadSafety.Unsafe } || meta.methods.any { it.safety != XrossThreadSafety.Direct && it.safety != XrossThreadSafety.Unsafe }
        is XrossDefinition.Enum -> true // Enums always have variants/locking logic for now
        is XrossDefinition.Opaque -> meta.fields.any { it.safety != XrossThreadSafety.Direct && it.safety != XrossThreadSafety.Unsafe } || meta.methods.any { it.safety != XrossThreadSafety.Direct && it.safety != XrossThreadSafety.Unsafe }
        else -> false
    }

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
                "suspend",
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
    fun buildFullGetter(kType: TypeName, readCode: CodeBlock, useAsyncLock: Boolean = true, safety: org.xross.structures.XrossThreadSafety = org.xross.structures.XrossThreadSafety.Lock): FunSpec {
        if (safety == org.xross.structures.XrossThreadSafety.Direct) {
            return FunSpec.getterBuilder()
                .addStatement("var res: %T", kType)
                .addCode(readCode)
                .addStatement("return res")
                .build()
        }

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
        if (safety == org.xross.structures.XrossThreadSafety.Direct) {
            return FunSpec.setterBuilder()
                .addParameter("v", kType)
                .addCode(writeCode)
                .build()
        }

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
     * Returns the name of the MethodHandle for a given XrossMethod.
     */
    fun getHandleName(method: org.xross.structures.XrossMethod): String = if (method.isDefault) {
        "defaultHandle"
    } else if (method.name == "new") {
        "newHandle"
    } else {
        "${method.name.toCamelCase()}Handle"
    }

    /**
     * Returns the name of the MethodHandle for a given property and type.
     */
    fun getPropertyHandleName(baseName: String, type: XrossType, isGet: Boolean): String {
        val suffix = when (type) {
            is XrossType.Optional -> "Opt"
            is XrossType.Result -> "Res"
            is XrossType.RustString -> "Str"
            else -> ""
        }
        val action = if (isGet) "Get" else "Set"
        return "${baseName}${suffix}${action}Handle"
    }

    /**
     * Adds common lock properties (sl, fl, al) to a type builder.
     */
    fun addLockProperties(builder: TypeSpec.Builder, basePackage: String) {
        val runtimePkg = "$basePackage.xross.runtime"
        builder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.sl").build()).build())
        builder.addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.fl").build()).build())
        builder.addProperty(PropertySpec.builder("al", ClassName(runtimePkg, "XrossAsyncLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.al").build()).build())
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
        val xrossObject = ClassName("$basePackage.xross.runtime", "XrossObject")
        return Triple::class.asClassName().parameterizedBy(MEMORY_SEGMENT, xrossObject.copy(nullable = true), BOOLEAN)
    }

    /**
     * Returns an allocation expression based on the XrossType.
     */
    fun generateAllocMsg(ty: XrossType, valueName: String, arenaName: String = "java.lang.foreign.Arena.ofAuto()"): CodeBlock = when (ty) {
        is XrossType.Object -> CodeBlock.of("$valueName.segment")
        is XrossType.RustString -> CodeBlock.of("$arenaName.allocateFrom($valueName)")
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
        xrossObject: TypeName,
        firstParamName: String = "ptr",
    ): FunSpec.Builder = this.addParameter(firstParamName, MEMORY_SEGMENT)
        .addParameter("parent", xrossObject.copy(nullable = true))
        .addParameter("isPersistent", BOOLEAN)

    /**
     * Builds the base for a 'fromPointer' method.
     */
    fun buildFromPointerBase(
        name: String,
        returnType: TypeName,
        basePackage: String,
    ): FunSpec.Builder {
        val xrossObject = ClassName("$basePackage.xross.runtime", "XrossObject")

        return FunSpec.builder(name)
            .addInternalParameters(xrossObject, "ptr")
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
        if (field.ty is XrossType.Object || field.ty is XrossType.RustString) {
            val backingFieldName = "_$baseName"
            val backingProp =
                PropertySpec.builder(backingFieldName, kType.copy(nullable = true))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .addAnnotation(ClassName("kotlin.jvm", "Volatile"))
                    .initializer("null")
                    .build()
            builder.addProperty(backingProp)
            return backingFieldName
        }
        return null
    }

    fun buildRawInitializer(
        builder: TypeSpec.Builder,
        xrossObject: ClassName,
    ) {
        builder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("raw", MEMORY_SEGMENT)
                .addParameter("parent", xrossObject.copy(nullable = true))
                .addParameter("isPersistent", BOOLEAN)
                .build(),
        )
        builder.addSuperclassConstructorParameter("raw")
        builder.addSuperclassConstructorParameter("parent")
        builder.addSuperclassConstructorParameter("isPersistent")
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
                    CodeBlock.of("p.second"),
                    CodeBlock.of("p.third"),
                )
                .addCode("this.registerNativeCleaner(dropHandle)\n")
                .build(),
        )
    }

    /**
     * Adds a check to ensure the object is still alive (not NULL and valid).
     */
    fun addAliveCheck(body: CodeBlock.Builder, message: String = "Access error") {
        body.addStatement("if (this.segment == %T.NULL || !this.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, message)
    }

    /**
     * Adds a 'clearCache' function to clear backing properties.
     */
    fun addClearCacheFunction(builder: TypeSpec.Builder, backingFields: List<String>) {
        if (backingFields.isEmpty()) return
        val clearCache = FunSpec.builder("clearCache")
            .addModifiers(KModifier.OVERRIDE)
            .apply {
                backingFields.forEach { addStatement("this.$it = null") }
            }
            .build()
        builder.addFunction(clearCache)
    }

    /**
     * Prepares arguments and optionally an Arena if needed by the arguments.
     * Returns the name of the arena to use.
     */
    fun prepareArgumentsAndArena(
        method: org.xross.structures.XrossMethod,
        body: CodeBlock.Builder,
        basePackage: String,
        callArgs: MutableList<CodeBlock>,
        checkObjectValidity: Boolean = false,
        arenaName: String? = null,
        namePrefix: String = "",
    ): String = prepareArgumentsAndArena(
        method.args,
        method.handleMode,
        body,
        basePackage,
        callArgs,
        checkObjectValidity,
        arenaName,
        namePrefix,
    )

    fun prepareArgumentsAndArena(
        args: List<org.xross.structures.XrossField>,
        handleMode: org.xross.structures.HandleMode,
        body: CodeBlock.Builder,
        basePackage: String,
        callArgs: MutableList<CodeBlock>,
        checkObjectValidity: Boolean = false,
        arenaName: String? = null,
        namePrefix: String = "",
    ): String {
        val needsArena = args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }
        val finalArenaName = arenaName ?: if (needsArena) "arena" else "java.lang.foreign.Arena.ofAuto()"

        if (needsArena && arenaName == null) {
            body.beginControlFlow("%T.ofConfined().use { arena ->", ARENA)
        }

        args.forEach { arg ->
            val name = (namePrefix + arg.name.toCamelCase()).escapeKotlinKeyword()
            body.addArgumentPreparation(
                arg.ty,
                name,
                callArgs,
                checkObjectValidity = checkObjectValidity,
                basePackage = basePackage,
                handleMode = handleMode,
                arenaName = finalArenaName,
            )
        }
        return finalArenaName
    }
}
