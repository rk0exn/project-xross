package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

object MethodGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

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

            val returnType = resolveReturnType(method.ret, basePackage)
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
                funBuilder.addParameter(arg.name.toCamelCase().escapeKotlinKeyword(), resolveReturnType(arg.ty, basePackage))
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
            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                when (arg.ty) {
                    is XrossType.RustString -> {
                        argPrep.addStatement("val ${name}Memory = this.autoArena.allocateFrom($name)")
                        callArgs.add(CodeBlock.of("${name}Memory"))
                    }
                    is XrossType.Object -> {
                        argPrep.beginControlFlow("if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)", MEMORY_SEGMENT)
                        argPrep.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Arg invalid")
                        argPrep.endControlFlow()
                        callArgs.add(CodeBlock.of("$name.segment"))
                    }
                    is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
                    else -> callArgs.add(CodeBlock.of("%L", name))
                }
            }

            val needsArena = method.args.any { it.ty is XrossType.RustString }
            if (needsArena) {
                body.beginControlFlow("%T.ofConfined().use { _ ->", Arena::class.asTypeName())
                body.add(argPrep.build())
            } else {
                body.add(argPrep.build())
            }

            val handleName = "${method.name.toCamelCase()}Handle"
            val call = if (method.ret is XrossType.Result) {
                CodeBlock.of("$handleName.invokeExact(this.autoArena as %T, %L)", ClassName("java.lang.foreign", "SegmentAllocator"), callArgs.joinToCode(", "))
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

    private fun resolveReturnType(type: XrossType, basePackage: String): TypeName = when (type) {
        is XrossType.RustString -> String::class.asTypeName()
        is XrossType.Object -> GeneratorUtils.getClassName(type.signature, basePackage)
        is XrossType.Optional -> resolveReturnType(type.inner, basePackage).copy(nullable = true)
        is XrossType.Result -> ClassName("kotlin", "Result").parameterizedBy(resolveReturnType(type.ok, basePackage))
        else -> type.kotlinType
    }

    private fun applyMethodCall(method: XrossMethod, call: CodeBlock, returnType: TypeName, selfType: ClassName, basePackage: String, meta: XrossDefinition): CodeBlock {
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
                    body.addStatement("    %N -> new${v.name}Handle.invokeExact() as %T", v.name, MEMORY_SEGMENT)
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

    private fun generateInvokeLogic(method: XrossMethod, call: CodeBlock, returnType: TypeName, selfType: ClassName, basePackage: String): CodeBlock {
        val body = CodeBlock.builder()
        val runtimePkg = "$basePackage.xross.runtime"
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
            is XrossType.Optional -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                body.beginControlFlow("if (resRaw == %T.NULL)", MEMORY_SEGMENT).addStatement("null").nextControlFlow("else")
                val isSelf = returnType.copy(nullable = false) == selfType
                val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", returnType.copy(nullable = false))
                val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
                when (val inner = retTy.inner) {
                    is XrossType.Object -> {
                        val innerType = returnType.copy(nullable = false)
                        val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", innerType)
                        val dropExpr = if (isSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", innerType)

                        if (inner.isOwned) {
                            body.addStatement("val retAutoArena = Arena.ofAuto()")
                            body.addStatement("val retOwnerArena = Arena.ofAuto()")
                            body.addStatement("val flag = %T(true)", flagType)
                            body.addStatement("val res = resRaw.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }", sizeExpr, dropExpr)
                            body.addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", fromPointerExpr)
                        } else {
                            body.addStatement("%L(resRaw, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                        }
                    }
                    is XrossType.RustString -> {
                        GeneratorUtils.addRustStringResolution(body, "resRaw")
                        body.addStatement("str")
                    }
                    else -> body.addStatement("val valRes = resRaw.get(%M, 0)\ndropHandle.invokeExact(resRaw)\nvalRes", inner.layoutMember)
                }
                body.endControlFlow().endControlFlow()
            }
            is XrossType.Object -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                body.beginControlFlow("if (resRaw == %T.NULL)", MEMORY_SEGMENT).addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "NULL").endControlFlow()
                val isSelf = returnType == selfType

                val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", returnType)
                val dropExpr = if (isSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", returnType)
                val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", returnType)
                val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

                if (retTy.isOwned) {
                    body.addStatement("val retAutoArena = Arena.ofAuto()")
                    body.addStatement("val retOwnerArena = Arena.ofAuto()")
                    body.addStatement("val flag = %T(true)", flagType)
                    body.addStatement("val res = resRaw.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }", sizeExpr, dropExpr)
                    body.addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", fromPointerExpr)
                } else {
                    body.addStatement("%L(resRaw, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                }
                body.endControlFlow()
            }
            is XrossType.Result -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
                body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()",
                    MemberName(ClassName("java.lang.foreign", "ValueLayout"), "JAVA_BYTE")
                )
                body.addStatement("val ptr = resRaw.get(%M, 8L)", ADDRESS)
                body.beginControlFlow("if (isOk)")

                val okType = resolveReturnType(retTy.ok, basePackage)
                val isOkSelf = okType == selfType
                val okSizeExpr = if (isOkSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", okType)
                val okDropExpr = if (isOkSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", okType)
                val okFromPointerExpr = if (isOkSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", okType)
                val okFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

                body.beginControlFlow("val okVal = run")
                when (val okTy = retTy.ok) {
                    is XrossType.Object -> {
                        body.addStatement("val retAutoArena = Arena.ofAuto()")
                        body.addStatement("val retOwnerArena = Arena.ofAuto()")
                        body.addStatement("val flag = %T(true)", okFlagType)
                        body.addStatement("val res = ptr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }", okSizeExpr, okDropExpr)
                        body.addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", okFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        GeneratorUtils.addRustStringResolution(body, "ptr")
                        body.addStatement("str")
                    }
                    else -> body.addStatement("val v = ptr.get(%M, 0)\ndropHandle.invokeExact(ptr)\nv", okTy.layoutMember)
                }
                body.endControlFlow()
                body.addStatement("Result.success(okVal)")

                body.nextControlFlow("else")

                val errType = resolveReturnType(retTy.err, basePackage)
                val isErrSelf = errType == selfType
                val errSizeExpr = if (isErrSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", errType)
                val errDropExpr = if (isErrSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", errType)
                val errFromPointerExpr = if (isErrSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", errType)
                val errFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

                body.beginControlFlow("val errVal = run")
                when (val errTy = retTy.err) {
                    is XrossType.Object -> {
                        body.addStatement("val retAutoArena = Arena.ofAuto()")
                        body.addStatement("val retOwnerArena = Arena.ofAuto()")
                        body.addStatement("val flag = %T(true)", errFlagType)
                        body.addStatement("val res = ptr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }", errSizeExpr, errDropExpr)
                        body.addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", errFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        GeneratorUtils.addRustStringResolution(body, "ptr")
                        body.addStatement("str")
                    }
                    else -> body.addStatement("val v = ptr.get(%M, 0)\ndropHandle.invokeExact(ptr)\nv", errTy.layoutMember)
                }
                body.endControlFlow()
                body.addStatement("Result.failure(%T(errVal))", ClassName(runtimePkg, "XrossException"))
                body.endControlFlow()
                body.endControlFlow()
            }
            else -> body.addStatement("%L as %T", call, returnType)
        }
        return body.build()
    }

    private fun generatePublicConstructor(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        method: XrossMethod,
        basePackage: String,
    ) {
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)

        val factoryBuilder = FunSpec.builder("xrossNewInternal").addModifiers(KModifier.PRIVATE)
            .addParameters(method.args.map { ParameterSpec.builder("argOf" + it.name.toCamelCase(), resolveReturnType(it.ty, basePackage)).build() })
            .returns(tripleType)

        val body = CodeBlock.builder()
        val callArgs = mutableListOf<CodeBlock>()
        method.args.forEach { arg ->
            val name = "argOf" + arg.name.toCamelCase()
            when (arg.ty) {
                is XrossType.RustString -> {
                    body.addStatement("val ${name}Memory = %T.ofAuto().allocateFrom($name)", Arena::class)
                    callArgs.add(CodeBlock.of("${name}Memory"))
                }
                is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
                is XrossType.Object -> callArgs.add(CodeBlock.of("$name.segment"))
                else -> callArgs.add(CodeBlock.of("%L", name))
            }
        }

        GeneratorUtils.addFactoryBody(
            body,
            basePackage,
            CodeBlock.of("newHandle.invokeExact(%L)", callArgs.joinToCode(", ")),
            CodeBlock.of("STRUCT_SIZE"),
            CodeBlock.of("dropHandle"),
        )
        body.addStatement("return %T(res, %T(newAutoArena, newOwnerArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())

        factoryBuilder.addCode(body.build())
        companionBuilder.addFunction(factoryBuilder.build())

        classBuilder.addFunction(FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).addParameter("p", tripleType).callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second.first"), CodeBlock.of("p.second.second"), CodeBlock.of("p.third")).build())
        classBuilder.addFunction(
            FunSpec.constructorBuilder().addParameters(method.args.map { ParameterSpec.builder("argOf" + it.name.toCamelCase(), resolveReturnType(it.ty, basePackage)).build() })
                .callThisConstructor(CodeBlock.of("xrossNewInternal(${method.args.joinToString(", ") { "argOf" + it.name.toCamelCase() }})")).build(),
        )
    }
}
