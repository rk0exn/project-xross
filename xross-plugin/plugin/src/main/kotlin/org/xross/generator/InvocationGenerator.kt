package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment

object InvocationGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    fun applyMethodCall(
        method: XrossMethod,
        call: CodeBlock,
        returnType: TypeName,
        selfType: ClassName,
        basePackage: String,
        meta: XrossDefinition,
    ): CodeBlock {
        val isVoid = method.ret is XrossType.Void
        val body = CodeBlock.builder()

        // Prepare list of objects to lock (including self and arguments)
        val targets = mutableListOf<LockTarget>()
        if (method.methodType != XrossMethodType.Static) {
            val isMut = method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance
            targets.add(LockTarget("this", isMut, isSelf = true))
        }
        method.args.forEach { arg ->
            if (arg.ty is XrossType.Object) {
                val isMut = arg.ty.ownership == XrossType.Ownership.MutRef || arg.ty.ownership == XrossType.Ownership.Owned || arg.ty.ownership == XrossType.Ownership.Boxed
                targets.add(LockTarget(arg.name.toCamelCase().escapeKotlinKeyword(), isMut, isSelf = false))
            }
        }

        if (!isVoid) body.addStatement("var resValue: %T", returnType)

        // Helper to generate nested locks
        fun wrapWithLocks(index: Int) {
            if (index >= targets.size) {
                if (!isVoid) body.add("resValue = ")
                body.add(generateInvokeLogic(method, call, returnType, selfType, basePackage))
                return
            }

            val target = targets[index]
            val suffix = if (method.isAsync) "" else "Blocking"
            val action = if (target.isMutable) "Write" else "Read"

            val lockMethod = "lock$action$suffix"
            val unlockMethod = "unlock$action$suffix"

            // If target is self, use sl as well for sync-async consistency
            val useSl = target.isSelf

            body.addStatement("${target.name}.al.%L()", lockMethod)
            body.beginControlFlow("try")

            if (useSl) {
                val isMutable = target.isMutable
                val slLockMethod = if (isMutable) "writeLock" else "readLock"

                body.addStatement("val stamp = ${target.name}.sl.%L()", slLockMethod)
                body.beginControlFlow("try")
            }

            wrapWithLocks(index + 1)

            if (useSl) {
                val isMutable = target.isMutable
                val slUnlockMethod = if (isMutable) "unlockWrite" else "unlockRead"

                body.nextControlFlow("finally")
                body.addStatement("${target.name}.sl.%L(stamp)", slUnlockMethod)
                body.endControlFlow()
            }

            body.nextControlFlow("finally")
            body.addStatement("${target.name}.al.%L()", unlockMethod)
            body.endControlFlow()
        }

        // Special handling for Immutable synchronous locking (Fair lock)
        if (!method.isAsync && method.safety == XrossThreadSafety.Immutable && method.methodType != XrossMethodType.Static) {
            body.addStatement("this.fl.lock()")
            body.beginControlFlow("try")
            wrapWithLocks(0)
            body.nextControlFlow("finally")
            body.addStatement("this.fl.unlock()")
            body.endControlFlow()
        } else {
            wrapWithLocks(0)
        }

        // Post-call ownership logic (Relinquish)
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
                body.addStatement("this.relinquishInternal()")
            }
        }

        method.args.forEach { arg ->
            if (arg.ty is XrossType.Object && arg.ty.isOwned) {
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                body.addStatement("%L.relinquish()", name)
            }
        }

        if (!isVoid) body.addStatement("resValue")
        return body.build()
    }

    private data class LockTarget(val name: String, val isMutable: Boolean, val isSelf: Boolean)

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
        val isPanicable = method.handleMode is HandleMode.Panicable

        fun getExprs(type: TypeName) = Triple(
            if (type == selfType) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", type),
            if (type == selfType) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", type),
            if (type == selfType) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", type),
        )

        if (method.isAsync) {
            body.beginControlFlow("run")
            body.addStatement("val task = %L as %T", call, MEMORY_SEGMENT)
            body.addStatement("val taskPtr = task.get(%M, 0L)", ADDRESS)
            body.addStatement("val pollFnPtr = task.get(%M, 8L)", ADDRESS)
            body.addStatement("val dropFnPtr = task.get(%M, 16L)", ADDRESS)

            body.addStatement("val pollFn = linker.downcallHandle(pollFnPtr, %T.of(%L, %M))", FFMConstants.FUNCTION_DESCRIPTOR, FFMConstants.XROSS_RESULT_LAYOUT_CODE, ADDRESS)
            body.addStatement("val dropFn = linker.downcallHandle(dropFnPtr, %T.ofVoid(%M))", FFMConstants.FUNCTION_DESCRIPTOR, ADDRESS)

            body.beginControlFlow("%T.awaitFuture(taskPtr, pollFn, dropFn)", ClassName(runtimePkg, "XrossAsync"))
            body.addResultVariantResolution(
                method.ret,
                "it",
                returnType,
                selfType,
                basePackage,
                "dropHandle",
            )
            body.endControlFlow()
            body.endControlFlow()
            return body.build()
        }

        if (isPanicable) {
            body.beginControlFlow("run")
            body.addStatement("val resRaw = %L as %T", call, MEMORY_SEGMENT)
            body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", FFMConstants.JAVA_BYTE)
            body.addStatement("val ptr = resRaw.get(%M, 8L)", ADDRESS)
            body.beginControlFlow("if (!isOk)")
            body.add("val errVal = ")
            body.addResultVariantResolution(
                XrossType.RustString,
                "ptr",
                String::class.asTypeName(),
                selfType,
                basePackage,
                "dropHandle",
            )
            body.addStatement("throw %T(errVal)", ClassName(runtimePkg, "XrossException"))
            body.endControlFlow()

            // Success case: resolve the actual return type
            if (method.ret is XrossType.Void) {
                body.addStatement("Unit")
            } else {
                body.add("val okVal = ")
                body.addResultVariantResolution(
                    method.ret,
                    "ptr",
                    returnType,
                    selfType,
                    basePackage,
                    "dropHandle",
                )
                body.addStatement("okVal")
            }
            body.endControlFlow()
            return body.build()
        }

        when (val retTy = method.ret) {
            is XrossType.Void -> {
                body.addStatement("%L as Unit", call)
            }

            is XrossType.RustString -> {
                body.beginControlFlow("run")
                body.addRustStringResolution(call)
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
                body.addResultVariantResolution(retTy.inner, "resRaw", innerType, selfType, basePackage, "dropHandle")
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
                    "dropHandle",
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
                    "dropHandle",
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
}
