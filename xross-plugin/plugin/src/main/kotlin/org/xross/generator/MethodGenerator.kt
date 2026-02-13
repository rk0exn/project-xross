package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator

/**
 * Generates Kotlin methods that wrap native Rust functions using Java FFM.
 */
object MethodGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    /**
     * Generates Kotlin methods for all methods defined in the metadata.
     */
    fun generateMethods(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition,
        basePackage: String,
    ) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        val isEnum = meta is XrossDefinition.Enum

        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct) {
                    generatePublicConstructor(
                        classBuilder,
                        companionBuilder,
                        method,
                        basePackage,
                    )
                }
                return@forEach
            }

            if (isEnum && method.name == "clone") return@forEach

            val returnType = GeneratorUtils.resolveReturnType(method.ret, basePackage)
            val kotlinName = method.name.toCamelCase().escapeKotlinKeyword()
            val funBuilder = FunSpec.builder(kotlinName).returns(returnType)

            // Avoid clash with property accessors
            val fields = when (meta) {
                is XrossDefinition.Struct -> meta.fields
                is XrossDefinition.Opaque -> meta.fields
                else -> emptyList()
            }
            val hasClash = fields.any {
                val base = it.name.toCamelCase().replaceFirstChar { c -> c.uppercase() }
                kotlinName == "get$base" || kotlinName == "set$base"
            }
            if (hasClash) {
                funBuilder.addAnnotation(
                    AnnotationSpec.builder(JvmName::class)
                        .addMember("%S", "xross_${method.name.toCamelCase()}")
                        .build(),
                )
            }

            method.args.forEach { arg ->
                funBuilder.addParameter(
                    arg.name.toCamelCase().escapeKotlinKeyword(),
                    GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                )
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = this.segment")
                body.beginControlFlow("if (currentSegment == %T.NULL || !this.aliveFlag.isValid)", MEMORY_SEGMENT)
                body.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")
            val callArgs = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) callArgs.add(CodeBlock.of("currentSegment"))

            val argPrep = CodeBlock.builder()
            val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }
            
            if (needsArena) {
                argPrep.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class.asTypeName())
            }

            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                when (arg.ty) {
                    is XrossType.RustString -> {
                        argPrep.addStatement("val ${name}Memory = arena.allocateFrom($name)")
                        callArgs.add(CodeBlock.of("${name}Memory"))
                    }

                    is XrossType.Object -> {
                        argPrep.beginControlFlow(
                            "if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)",
                            MEMORY_SEGMENT,
                        )
                        argPrep.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Arg invalid")
                        argPrep.endControlFlow()
                        callArgs.add(CodeBlock.of("$name.segment"))
                    }

                    is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
                    is XrossType.Optional -> {
                        argPrep.addStatement("val ${name}Memory = if ($name == null) %T.NULL else %L", MEMORY_SEGMENT, generateAllocMsg(arg.ty.inner, name))
                        callArgs.add(CodeBlock.of("${name}Memory"))
                    }
                    is XrossType.Result -> {
                        argPrep.addStatement("val ${name}Memory = arena.allocate(%L)", FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                        argPrep.beginControlFlow("if ($name.isSuccess)")
                        argPrep.addStatement("${name}Memory.set(%M, 0L, 1.toByte())", FFMConstants.JAVA_BYTE)
                        argPrep.addStatement("${name}Memory.set(%M, 8L, %L)", FFMConstants.ADDRESS, generateAllocMsg(arg.ty.ok, "$name.getOrNull()!!"))
                        argPrep.nextControlFlow("else")
                        argPrep.addStatement("${name}Memory.set(%M, 0L, 0.toByte())", FFMConstants.JAVA_BYTE)
                        argPrep.addStatement("${name}Memory.set(%M, 8L, %T.NULL)", FFMConstants.ADDRESS, MEMORY_SEGMENT)
                        argPrep.endControlFlow()
                        callArgs.add(CodeBlock.of("${name}Memory"))
                    }
                    else -> callArgs.add(CodeBlock.of("%L", name))
                }
            }

            body.add(argPrep.build())

            val handleName = "Companion.${method.name.toCamelCase()}Handle"
            val call = if (method.ret is XrossType.Result) {
                CodeBlock.of(
                    "$handleName.invokeExact(this.autoArena as %T, %L)",
                    SegmentAllocator::class,
                    callArgs.joinToCode(", "),
                )
            } else {
                CodeBlock.of("$handleName.invokeExact(%L)", callArgs.joinToCode(", "))
            }
            body.add(applyMethodCall(method, call, returnType, selfType, basePackage, meta = meta))

            if (needsArena) body.endControlFlow()
            body.nextControlFlow("catch (e: Throwable)")
            body.addStatement("throw %T(e)", RuntimeException::class.asTypeName())
            body.endControlFlow()

            funBuilder.addCode(body.build())
            if (method.methodType == XrossMethodType.Static) {
                companionBuilder.addFunction(funBuilder.build())
            } else {
                classBuilder.addFunction(funBuilder.build())
            }
        }
    }

    private fun applyMethodCall(
        method: XrossMethod,
        call: CodeBlock,
        returnType: TypeName,
        selfType: ClassName,
        basePackage: String,
        meta: XrossDefinition,
    ): CodeBlock {
        val isVoid = method.ret is XrossType.Void
        val useLock = method.safety == XrossThreadSafety.Lock && method.methodType != XrossMethodType.Static
        val body = CodeBlock.builder()

        if (useLock) {
            if (!isVoid) body.addStatement("var resValue: %T", returnType)
            body.addStatement("val stamp = this.sl.writeLock()")
            body.beginControlFlow("try")
            if (!isVoid) body.add("resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, selfType, basePackage))
            body.nextControlFlow("finally")
            body.addStatement("this.sl.unlockWrite(stamp)")
            body.endControlFlow()
        } else {
            if (!isVoid) body.add("val resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, selfType, basePackage))
        }

        // Post-call logic for OwnedInstance
        if (method.methodType == XrossMethodType.OwnedInstance) {
            val isPureEnum = GeneratorUtils.isPureEnum(meta)
            val isCopy = meta.isCopy
            if (isPureEnum && !isCopy) {
                body.addStatement("// Re-initialize consumed segment for fieldless enum")
                body.addStatement("this.segment = when(this) {")
                (meta as XrossDefinition.Enum).variants.forEach { v ->
                    body.addStatement("    %N -> Companion.new${v.name}Handle.invokeExact() as %T", v.name, MEMORY_SEGMENT)
                }
                body.addStatement("}")
            } else if (!isCopy || !isPureEnum) {
                // If it's an Enum (complex) or Struct, and it's consumed, we relinquish it.
                // Note: Structs don't have isPureEnum true.
                body.addStatement("this.relinquishInternal()")
            }
        }

        // Relinquish owned arguments
        method.args.forEach { arg ->
            if (arg.ty is XrossType.Object && arg.ty.isOwned) {
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                body.addStatement("%L.relinquish()", name)
            }
        }

        if (!isVoid) body.addStatement("resValue")
        return body.build()
    }

    private fun generateInvokeLogic(
        method: XrossMethod,
        call: CodeBlock,
        returnType: TypeName,
        selfType: ClassName,
        basePackage: String,
    ): CodeBlock {
        val body = CodeBlock.builder()
        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val runtimePkg = "$basePackage.xross.runtime"

        // ヘルパー：型が自分自身(Self)かどうかでアクセスするプロパティ/関数を切り替える
        fun getExprs(type: TypeName) = Triple(
            if (type == selfType) CodeBlock.of("Companion.STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", type),
            if (type == selfType) CodeBlock.of("Companion.dropHandle") else CodeBlock.of("%T.dropHandle", type),
            if (type == selfType) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", type),
        )

        when (val retTy = method.ret) {
            is XrossType.Void -> {
                body.addStatement("%L as Unit", call)
            }

            is XrossType.RustString -> {
                body.beginControlFlow("run")
                GeneratorUtils.addRustStringResolution(body, call)
                body.addStatement("str")
                body.endControlFlow()
            }

            is XrossType.Object -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                body.beginControlFlow("if (resRaw == %T.NULL)", MEMORY_SEGMENT)
                    .addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Unexpected NULL return")
                body.nextControlFlow("else")
                val (size, drop, from) = getExprs(returnType)
                body.addResourceConstruction(retTy, "resRaw", size, from, drop, flagType)
                body.endControlFlow().endControlFlow()
            }

            is XrossType.Optional -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                body.beginControlFlow("if (resRaw == %T.NULL)", MEMORY_SEGMENT)
                    .addStatement("null")
                body.nextControlFlow("else")
                // Optionalの中身(inner)を解決
                val innerType = GeneratorUtils.resolveReturnType(retTy.inner, basePackage)
                body.addResultVariantResolution(retTy.inner, "resRaw", innerType, selfType, basePackage, "Companion.dropHandle")
                body.endControlFlow().endControlFlow()
            }

            is XrossType.Result -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                // Resultのレイアウト: [1 byte: isOk, 7 bytes: padding, 8 bytes: pointer]
                body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", FFMConstants.JAVA_BYTE)
                body.addStatement("val ptr = resRaw.get(%M, 8L)", ADDRESS)

                body.beginControlFlow("if (isOk)")
                body.add("val okVal = ")
                body.addResultVariantResolution(
                    retTy.ok,
                    "ptr",
                    GeneratorUtils.resolveReturnType(retTy.ok, basePackage),
                    selfType,
                    basePackage,
                    "Companion.dropHandle",
                )
                body.addStatement("Result.success(okVal)")

                body.nextControlFlow("else")
                body.add("val errVal = ")
                body.addResultVariantResolution(
                    retTy.err,
                    "ptr",
                    GeneratorUtils.resolveReturnType(retTy.err, basePackage),
                    selfType,
                    basePackage,
                    "Companion.dropHandle",
                )
                body.addStatement("Result.failure(%T(errVal))", ClassName(runtimePkg, "XrossException"))
                body.endControlFlow()
                body.endControlFlow()
            }
            // 数値型やBooleanなどのプリミティブ型
            else -> {
                body.addStatement("%L as %T", call, returnType)
            }
        }
        return body.build()
    }

    /**
     * Generates a public constructor for a struct.
     */
    private fun generatePublicConstructor(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        method: XrossMethod,
        basePackage: String,
    ) {
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)

        val factoryBuilder = FunSpec.builder("xrossNewInternal").addModifiers(KModifier.PRIVATE)
            .addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        "argOf" + it.name.toCamelCase(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
            .returns(tripleType)

        val body = CodeBlock.builder()
        val callArgs = mutableListOf<CodeBlock>()
        val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }
        
        if (needsArena) {
            body.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class)
        }

        method.args.forEach { arg ->
            val name = "argOf" + arg.name.toCamelCase()
            when (arg.ty) {
                is XrossType.RustString -> {
                    body.addStatement("val ${name}Memory = arena.allocateFrom($name)")
                    callArgs.add(CodeBlock.of("${name}Memory"))
                }

                is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
                is XrossType.Object -> callArgs.add(CodeBlock.of("$name.segment"))
                is XrossType.Optional -> {
                    body.addStatement("val ${name}Memory = if ($name == null) %T.NULL else %L", MEMORY_SEGMENT, generateAllocMsg(arg.ty.inner, name))
                    callArgs.add(CodeBlock.of("${name}Memory"))
                }
                is XrossType.Result -> {
                    body.addStatement("val ${name}Memory = arena.allocate(%L)", FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                    body.beginControlFlow("if ($name.isSuccess)")
                    body.addStatement("${name}Memory.set(%M, 0L, 1.toByte())", FFMConstants.JAVA_BYTE)
                    body.addStatement("${name}Memory.set(%M, 8L, %L)", FFMConstants.ADDRESS, generateAllocMsg(arg.ty.ok, "$name.getOrNull()!!"))
                    body.nextControlFlow("else")
                    body.addStatement("${name}Memory.set(%M, 0L, 0.toByte())", FFMConstants.JAVA_BYTE)
                    body.addStatement("${name}Memory.set(%M, 8L, %T.NULL)", FFMConstants.ADDRESS, MEMORY_SEGMENT)
                    body.endControlFlow()
                    callArgs.add(CodeBlock.of("${name}Memory"))
                }
                else -> callArgs.add(CodeBlock.of("%L", name))
            }
        }

        GeneratorUtils.addFactoryBody(
            body,
            basePackage,
            CodeBlock.of("Companion.newHandle.invokeExact(%L)", callArgs.joinToCode(", ")),
            CodeBlock.of("Companion.STRUCT_SIZE"),
            CodeBlock.of("Companion.dropHandle"),
        )
        body.addStatement(
            "return %T(res, %T(newAutoArena, newOwnerArena), flag)",
            Triple::class.asTypeName(),
            Pair::class.asTypeName(),
        )

        if (needsArena) {
            body.endControlFlow()
        }

        factoryBuilder.addCode(body.build())
        companionBuilder.addFunction(factoryBuilder.build())

        classBuilder.addFunction(
            FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).addParameter("p", tripleType)
                .callThisConstructor(
                    CodeBlock.of("p.first"),
                    CodeBlock.of("p.second.first"),
                    CodeBlock.of("p.second.second"),
                    CodeBlock.of("p.third"),
                ).build(),
        )
        classBuilder.addFunction(
            FunSpec.constructorBuilder().addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        "argOf" + it.name.toCamelCase(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
                .callThisConstructor(CodeBlock.of("xrossNewInternal(${method.args.joinToString(", ") { "argOf" + it.name.toCamelCase() }})"))
                .build(),
        )
    }

    // ヘルパー: 型に応じた allocate 式を返す
    private fun generateAllocMsg(ty: XrossType, valueName: String): CodeBlock = when (ty) {
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
}
