package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethod
import org.xross.structures.XrossMethodType
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object MethodGenerator {

    fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.methods.forEach { method ->
            if (method.isConstructor) {
                generatePublicConstructor(classBuilder, method)
                return@forEach
            }

            val isStringRet = method.ret is XrossType.RustString
            val isStructRet = method.ret is XrossType.Struct

            val returnType = when {
                isStringRet -> String::class.asTypeName()
                isStructRet -> {
                    val struct = method.ret
                    val name = if (struct.name == "Self") meta.structName else struct.name
                    ClassName("", name)
                }

                else -> method.ret.kotlinType
            }

            // メソッド名のエスケープ
            val funBuilder = FunSpec.builder(method.name.toCamelCase().escapeKotlinKeyword())
                .returns(returnType)

            // 引数名のエスケープ
            method.args.forEach {
                funBuilder.addParameter(it.name.toCamelCase().escapeKotlinKeyword(), it.ty.kotlinType)
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = segment")
                body.addStatement(
                    "if (currentSegment == %T.NULL) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Object dropped"
                )
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")

            val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Slice }
            if (needsArena) body.beginControlFlow(
                "%T.ofConfined().use { arena ->",
                ClassName("java.lang.foreign", "Arena")
            )

            val invokeArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) invokeArgs.add("currentSegment")

            // 引数呼び出し時もエスケープした名前を使用
            method.args.forEach {
                invokeArgs.add(it.name.toCamelCase().escapeKotlinKeyword())
            }

            // ハンドル名自体はエスケープ不要（内部プロパティのため）
            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString(", ")})"
            applyMethodCall(body, method, call, isStringRet, isStructRet, returnType)

            if (needsArena) body.endControlFlow()

            body.nextControlFlow("catch (e: Throwable)")
            body.addStatement("throw %T(e)", RuntimeException::class)
            body.endControlFlow()

            funBuilder.addCode(body.build())

            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun applyMethodCall(
        body: CodeBlock.Builder,
        method: XrossMethod,
        call: String,
        isStringRet: Boolean,
        isStructRet: Boolean,
        returnType: TypeName
    ) {
        // --- ロック戦略の決定 ---
        val safety = method.safety

        val lockType = when {
            // Unsafe または Atomic 指定の場合は JVM レベルの Lock をスキップ
            // (Atomic メソッドは Rust 内部で原子性を担保している、あるいは CAS 命令であることを想定)
            safety == XrossThreadSafety.Unsafe || safety == XrossThreadSafety.Atomic -> null

            // それ以外 (Lock) は従来通り methodType に基づいてロック
            method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance -> "writeLock().withLock"
            method.methodType == XrossMethodType.ConstInstance -> "readLock().withLock"
            else -> null
        }

        if (lockType != null) body.beginControlFlow("lock.%L", lockType)

        // --- 戻り値の処理 ---
        when {
            method.ret is XrossType.Void -> {
                body.addStatement("$call as Unit")
                // OwnedInstance (self 消費) の場合はセグメントを無効化
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                }
            }

            isStringRet -> {
                body.addStatement("val res = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val str = if (res == %T.NULL) \"\" else res.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class, Long::class
                )
                // Rust 側で malloc された文字列を解放
                body.addStatement("if (res != %T.NULL) xross_free_stringHandle.invokeExact(res)", MemorySegment::class)

                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                }
                body.addStatement("str")
            }

            isStructRet -> {
                val struct = method.ret as XrossType.Struct
                val structSize = "STRUCT_SIZE"

                body.addStatement("val resRaw = $call as %T", MemorySegment::class)
                body.addStatement(
                    "val res = if (resRaw == %T.NULL) resRaw else resRaw.reinterpret($structSize)",
                    MemorySegment::class
                )

                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                }
                // 返り値の構造体に親の生存フラグ（aliveFlag）を渡すことで、
                // 親が close されたらこの戻り値も無効になるようにする設計が望ましい
                body.addStatement("%T(res, isBorrowed = ${struct.isReference})", returnType)
            }

            else -> {
                // プリミティブ型などの処理
                if (method.methodType == XrossMethodType.OwnedInstance) {
                    body.addStatement("val res = $call as %T", returnType)
                    body.addStatement("segment = %T.NULL", MemorySegment::class)
                    body.addStatement("res")
                } else {
                    body.addStatement("$call as %T", returnType)
                }
            }
        }

        if (lockType != null) body.endControlFlow()
    }
    private fun generatePublicConstructor(classBuilder: TypeSpec.Builder, method: XrossMethod) {
        val builder = FunSpec.constructorBuilder()

        // 引数のセットアップ
        method.args.forEach {
            builder.addParameter(it.name.toCamelCase().escapeKotlinKeyword(), it.ty.kotlinType)
        }
        val args = method.args.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }
        val delegateCode = CodeBlock.builder()
            .add("(\n") // 改行して読みやすく
            .indent()
            .add("(newHandle.invokeExact($args) as %T).let { raw ->\n", MemorySegment::class)
            .add("    if (raw == %T.NULL) raw else raw.reinterpret(STRUCT_SIZE)\n", MemorySegment::class)
            .add("}),\n")
            .unindent()
            .add("false")
            .build()

        builder.callThisConstructor(delegateCode)
        classBuilder.addFunction(builder.build())
    }
}
