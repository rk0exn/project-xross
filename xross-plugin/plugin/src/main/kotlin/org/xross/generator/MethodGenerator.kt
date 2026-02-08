package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.Arena

object MethodGenerator {
    fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {


        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct) generatePublicConstructor(classBuilder, companionBuilder, method)
                return@forEach
            }

            val returnType = resolveReturnType(method.ret, meta)
            val isComplexRet = method.ret is XrossType.Object

            val funBuilder = FunSpec.builder(method.name.toCamelCase().escapeKotlinKeyword())
                .returns(returnType)

            method.args.forEach { arg ->
                funBuilder.addParameter(arg.name.toCamelCase().escapeKotlinKeyword(), resolveReturnType(arg.ty, meta))
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = this.segment")
                body.beginControlFlow("if (currentSegment == %T.NULL || !this.aliveFlag.isValid)", MemorySegment::class)
                body.addStatement("throw %T(%S)", NullPointerException::class, "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")

            val callArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) callArgs.add("currentSegment")

            val argPreparationBody = CodeBlock.builder()
            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                when (arg.ty) {
                    is XrossType.RustString -> {
                        argPreparationBody.addStatement("val ${name}Memory = this.arena.allocateFrom($name)")
                        callArgs.add("${name}Memory")
                    }
                    is XrossType.Object -> {
                        argPreparationBody.beginControlFlow("if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)", MemorySegment::class)
                        argPreparationBody.addStatement("throw %T(%S)", NullPointerException::class, "Argument '${arg.name}' cannot be NULL or invalid")
                        argPreparationBody.endControlFlow()
                        callArgs.add("$name.segment")
                    }
                    else -> callArgs.add(name)
                }
            }

            val needsArena = method.args.any { it.ty is XrossType.RustString }
            if (needsArena) {
                body.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class)
                body.add(argPreparationBody.build())
            } else {
                body.add(argPreparationBody.build())
            }

            val call = "${method.name}Handle.invokeExact(${callArgs.joinToString(", ")})"
            body.add(applyMethodCall(method, call, returnType, isComplexRet))

            if (needsArena) body.endControlFlow()

            body.nextControlFlow("catch (e: Throwable)")
            body.addStatement("throw %T(e)", RuntimeException::class)
            body.endControlFlow()

            funBuilder.addCode(body.build())

            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun resolveReturnType(type: XrossType, meta: XrossDefinition): TypeName {
        return when (type) {
            is XrossType.RustString -> String::class.asTypeName()
            is XrossType.Object -> {
                val signature = type.signature
                if (signature == "Self" || signature == meta.name || signature == "${meta.packageName}.${meta.name}") {
                    ClassName("", meta.name)
                } else {
                    val fqn = if (signature.contains(".")) signature else "${meta.packageName}.$signature"
                    val lastDot = fqn.lastIndexOf('.')
                    if (lastDot == -1) ClassName("", fqn)
                    else ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
                }
            }
            else -> type.kotlinType
        }
    }

    private fun applyMethodCall(method: XrossMethod, call: String, returnType: TypeName, isComplexRet: Boolean): CodeBlock {
        val isVoid = method.ret is XrossType.Void
        val safety = if (method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance) XrossThreadSafety.Immutable else method.safety
        val body = CodeBlock.builder()

        // 静的メソッドの場合はロックを使用しない (インスタンスがないため)
        val useLock = safety == XrossThreadSafety.Lock && method.methodType != XrossMethodType.Static

        if (useLock) {
            if (!isVoid) body.addStatement("var resValue: %T", returnType)
            body.addStatement("var stamp = this.sl.tryOptimisticRead()")
            if (!isVoid) body.add("resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet))

            body.beginControlFlow("if (!this.sl.validate(stamp))")
            body.addStatement("stamp = this.sl.readLock()")
            body.beginControlFlow("try")
            if (!isVoid) body.add("resValue = ")
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet))
            body.nextControlFlow("finally")
            body.addStatement("this.sl.unlockRead(stamp)")
            body.endControlFlow()
            body.endControlFlow()
            if (!isVoid) body.addStatement("resValue")
        } else {
            body.add(generateInvokeLogic(method, call, returnType, isComplexRet))
        }
        return body.build()
    }

    private fun generateInvokeLogic(method: XrossMethod, call: String, returnType: TypeName, isComplexRet: Boolean): CodeBlock {
        val body = CodeBlock.builder()
        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("this.aliveFlag.isValid = false")
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                }
            }
            method.ret is XrossType.RustString -> {
                body.beginControlFlow("run")
                body.addStatement("val res = $call as %T", MemorySegment::class)
                body.addStatement("val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)", MemorySegment::class, Long::class)
                body.addStatement("if (res != %T.NULL) xross_free_stringHandle.invokeExact(res)", MemorySegment::class)
                body.addStatement("str")
                body.endControlFlow()
            }
            isComplexRet -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement("if (resRaw == %T.NULL) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Native method '${method.name}' returned a NULL reference for type '$returnType'")
                val retType = method.ret as XrossType.Object
                if (retType.isOwned) {
                    body.addStatement("val retArena = Arena.ofConfined()")
                    body.addStatement("val res = resRaw.reinterpret(%T.STRUCT_SIZE, retArena) { s -> %T.dropHandle.invokeExact(s) }", returnType, returnType)
                    body.addStatement("%T(res, arena = retArena, isArenaOwner = true)", returnType)
                } else {
                    body.addStatement("val res = resRaw.reinterpret(%T.STRUCT_SIZE)", returnType)
                    if (method.methodType == XrossMethodType.Static) body.addStatement("%T(res, arena = Arena.global(), isArenaOwner = false)", returnType)
                    else body.addStatement("%T(res, arena = this.arena, isArenaOwner = false)", returnType)
                }
                body.endControlFlow()
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("this.aliveFlag.isValid = false")
                    body.addStatement("this.segment = %T.NULL", MemorySegment::class)
                }
            }
            else -> body.addStatement("$call as %T", returnType)
        }
        return body.build()
    }

    private fun generatePublicConstructor(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, method: XrossMethod) {
        val pairType = Pair::class.asClassName().parameterizedBy(MemorySegment::class.asClassName(), Arena::class.asClassName())
        val factoryBody = CodeBlock.builder()
            .addStatement("val newArena = Arena.ofAuto()")
            .addStatement("val raw = newHandle.invokeExact(${method.args.joinToString(", ") { "arg_" + it.name.toCamelCase() }}) as %T", MemorySegment::class)
            .beginControlFlow("if (raw == %T.NULL)", MemorySegment::class)
            .addStatement("throw %T(%S)", RuntimeException::class, "Failed to create native object")
            .endControlFlow()
            .addStatement("val res = raw.reinterpret(STRUCT_SIZE, newArena) { s -> dropHandle.invokeExact(s) }")
            .addStatement("return res to newArena")

        companionBuilder.addFunction(FunSpec.builder("xross_new_internal")
            .addModifiers(KModifier.PRIVATE)
            .addParameters(method.args.map { ParameterSpec.builder("arg_" + it.name.toCamelCase(), it.ty.kotlinType).build() })
            .returns(pairType)
            .addCode(factoryBody.build())
            .build())

        classBuilder.addFunction(FunSpec.constructorBuilder()
            .addModifiers(KModifier.PRIVATE)
            .addParameter("p", pairType)
            .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second"))
            .build())
        
        classBuilder.addFunction(FunSpec.constructorBuilder()
            .addParameters(method.args.map { ParameterSpec.builder("arg_" + it.name.toCamelCase(), it.ty.kotlinType).build() })
            .callThisConstructor(CodeBlock.of("xross_new_internal(${method.args.joinToString(", ") { "arg_" + it.name.toCamelCase() }})"))
            .build())
    }
}