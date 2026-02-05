package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

object MethodGenerator {
    // 収集した不透明オブジェクトの型（signature）を保存する
    val opaqueObjects: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()

    fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct) generatePublicConstructor(classBuilder, method)
                return@forEach
            }

            // 戻り値の型判定
            val returnType = resolveReturnType(method.ret, meta)
            val isComplexRet = method.ret is XrossType.RustStruct ||
                    method.ret is XrossType.RustEnum ||
                    method.ret is XrossType.Object

            val funBuilder = FunSpec.builder(method.name.toCamelCase().escapeKotlinKeyword())
                .returns(returnType)

            // 引数の追加
            method.args.forEach { arg ->
                funBuilder.addParameter(arg.name.toCamelCase().escapeKotlinKeyword(), resolveReturnType(arg.ty, meta))
            }

            val body = CodeBlock.builder()
            // インスタンス生存確認
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = segment")
                body.beginControlFlow("if (currentSegment == %T.NULL)", MemorySegment::class)
                body.addStatement("throw %T(%S)", NullPointerException::class, "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")

            // Arenaが必要なケース（文字列引数）
            val needsArena = method.args.any { it.ty is XrossType.RustString }
            if (needsArena) {
                body.beginControlFlow("%T.ofConfined().use { arena ->", ClassName("java.lang.foreign", "Arena"))
            }

            val invokeArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) invokeArgs.add("currentSegment")

            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                when (arg.ty) {
                    is XrossType.RustString -> invokeArgs.add("arena.allocateFrom($name)")
                    is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object -> invokeArgs.add("$name.segment")
                    else -> invokeArgs.add(name)
                }
            }

            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString(", ")})"
            applyMethodCall(body, method, call, returnType, isComplexRet)

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
            is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object -> {
                val signature = when (type) {
                    is XrossType.RustStruct -> type.signature
                    is XrossType.RustEnum -> type.signature
                    is XrossType.Object -> {
                        opaqueObjects.add(type.signature) // Opaque型として記録
                        type.signature
                    }

                }

                if (signature == "Self" || signature == meta.name || signature == "${meta.packageName}.${meta.name}") {
                    // 自分自身なら単純名
                    ClassName("", meta.name)
                } else {
                    // 外部パッケージなら packageName.signature
                    val fqn = if (signature.contains(".")) signature else "${meta.packageName}.$signature"
                    val lastDot = fqn.lastIndexOf('.')
                    ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
                }
            }

            else -> type.kotlinType
        }
    }

    private fun applyMethodCall(
        body: CodeBlock.Builder,
        method: XrossMethod,
        call: String,
        returnType: TypeName,
        isComplexRet: Boolean
    ) {
        val isVoid = method.ret is XrossType.Void
        val safety =
            if (method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance) {
                XrossThreadSafety.Immutable // 書き込みロック強制
            } else {
                method.safety
            }

        when (safety) {
            XrossThreadSafety.Lock -> {
                if (!isVoid) body.addStatement("var resValue: %T", returnType)
                body.addStatement("var stamp = sl.tryOptimisticRead()")
                if (!isVoid) body.add("resValue = ")
                generateInvokeLogic(body, method, call, returnType, isComplexRet)

                body.beginControlFlow("if (!sl.validate(stamp))")
                body.addStatement("stamp = sl.readLock()")
                body.beginControlFlow("try")
                if (!isVoid) body.add("resValue = ")
                generateInvokeLogic(body, method, call, returnType, isComplexRet)
                body.nextControlFlow("finally")
                body.addStatement("sl.unlockRead(stamp)")
                body.endControlFlow()
                body.endControlFlow()
                if (!isVoid) body.addStatement("resValue")
            }

            else -> generateInvokeLogic(body, method, call, returnType, isComplexRet)
        }
    }

    private fun generateInvokeLogic(
        body: CodeBlock.Builder,
        method: XrossMethod,
        call: String,
        returnType: TypeName,
        isComplexRet: Boolean
    ) {
        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement(
                    "this.segment = %T.NULL",
                    MemorySegment::class
                )
            }

            method.ret is XrossType.RustString -> {
                // ... (既存の文字列処理) ...
                body.beginControlFlow("run")
                body.addStatement("val res = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
                body.addStatement("if (res != %T.NULL) xross_free_stringHandle.invokeExact(res)", MemorySegment::class)
                body.addStatement("str")
                body.endControlFlow()
            }

            isComplexRet -> {
                body.beginControlFlow("run")
                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val res = if (resRaw == %T.NULL) resRaw else resRaw.reinterpret(STRUCT_SIZE)",
                    MemorySegment::class
                )

                // --- ここが修正箇所 ---
                // メタデータの Ownership を判定
                val isBorrowed = when (val retType = method.ret) {
                    is XrossType.RustStruct -> retType.ownership != XrossType.Ownership.Owned
                    is XrossType.RustEnum -> retType.ownership != XrossType.Ownership.Owned
                    is XrossType.Object -> retType.ownership != XrossType.Ownership.Owned
                    else -> false
                }
                val parent = if (isBorrowed) {
                    "this"
                } else {
                    "null"
                }

                // 動的に isBorrowed の値を埋め込む
                body.addStatement("%T(res, parent=$parent)", returnType)
                // ----------------------

                body.endControlFlow()
            }

            else -> body.addStatement("$call as %T", returnType)
        }
    }

    private fun generatePublicConstructor(classBuilder: TypeSpec.Builder, method: XrossMethod) {
        val builder = FunSpec.constructorBuilder()
        method.args.forEach { builder.addParameter(it.name.toCamelCase().escapeKotlinKeyword(), it.ty.kotlinType) }
        val args = method.args.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }

        builder.callThisConstructor(
            CodeBlock.of(
                "((newHandle.invokeExact($args) as %T).let { raw -> if (raw == %T.NULL) raw else raw.reinterpret(STRUCT_SIZE) }), false",
                MemorySegment::class, MemorySegment::class
            )
        )
        classBuilder.addFunction(builder.build())
    }
}
