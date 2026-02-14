package org.xross.generator.util

import com.squareup.kotlinpoet.*
import org.xross.generator.util.FFMConstants.MEMORY_SEGMENT
import org.xross.structures.XrossType
import java.lang.foreign.Arena

fun CodeBlock.Builder.addResourceConstruction(
    inner: XrossType,
    resRaw: String,
    sizeExpr: CodeBlock,
    fromPointerExpr: CodeBlock,
    dropExpr: CodeBlock,
    flagType: ClassName,
) {
    if (inner.isOwned) {
        addStatement("val retAutoArena = %T.ofAuto()", Arena::class)
        addStatement("val retOwnerArena = %T.ofAuto()", Arena::class)
        addStatement("val flag = %T(true)", flagType)
        addStatement(
            "val res = %L.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
            resRaw,
            sizeExpr,
            dropExpr,
        )
        addStatement("%L(res, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", fromPointerExpr)
    } else {
        addStatement(
            "%L(%L, this.autoArena, sharedFlag = %T(true, this.aliveFlag))",
            fromPointerExpr,
            resRaw,
            flagType,
        )
    }
}

fun CodeBlock.Builder.addFactoryBody(
    basePackage: String,
    handleCall: CodeBlock,
    structSizeExpr: CodeBlock,
    dropHandleExpr: CodeBlock,
): CodeBlock.Builder {
    val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
    val arena = ClassName("java.lang.foreign", "Arena")
    val memorySegment = ClassName("java.lang.foreign", "MemorySegment")

    addStatement("val newAutoArena = %T.ofAuto()", arena)
    addStatement("val newOwnerArena = %T.ofAuto()", arena)
    addStatement("val flag = %T(true)", aliveFlagType)
    addStatement("val resRaw = %L as %T", handleCall, memorySegment)
    addStatement(
        "if (resRaw == %T.NULL) throw %T(%S)",
        memorySegment,
        RuntimeException::class.asTypeName(),
        "Fail",
    )
    addStatement(
        "val res = resRaw.reinterpret(%L, newAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
        structSizeExpr,
        dropHandleExpr,
    )
    return this
}

fun CodeBlock.Builder.addResultAllocation(
    ty: XrossType.Result,
    valueName: String,
    targetMemoryName: String,
): CodeBlock.Builder {
    addStatement("val $targetMemoryName = arena.allocate(%L)", FFMConstants.XROSS_RESULT_LAYOUT_CODE)
    beginControlFlow("if ($valueName.isSuccess)")
    addStatement("$targetMemoryName.set(%M, 0L, 1.toByte())", FFMConstants.JAVA_BYTE)
    addStatement("$targetMemoryName.set(%M, 8L, %L)", FFMConstants.ADDRESS, GeneratorUtils.generateAllocMsg(ty.ok, "$valueName.getOrNull()!!"))
    nextControlFlow("else")
    addStatement("$targetMemoryName.set(%M, 0L, 0.toByte())", FFMConstants.JAVA_BYTE)
    addStatement("$targetMemoryName.set(%M, 8L, %T.NULL)", FFMConstants.ADDRESS, ClassName("java.lang.foreign", "MemorySegment"))
    endControlFlow()
    return this
}

fun CodeBlock.Builder.addRustStringResolution(call: Any, resultVar: String = "str", isAssignment: Boolean = false, shouldFree: Boolean = true): CodeBlock.Builder {
    val resRawName = if (call is String && call.endsWith("Raw")) call else "${resultVar}RawInternal"
    if (!(call is String && call == resRawName)) {
        addStatement("val $resRawName = %L as %T", call, MEMORY_SEGMENT)
    }
    val declaration = if (isAssignment) "" else "val "
    addStatement(
        "%L%L = if ($resRawName == %T.NULL) \"\" else $resRawName.reinterpret(%T.MAX_VALUE).getString(0)",
        declaration,
        resultVar,
        MEMORY_SEGMENT,
        Long::class.asTypeName(),
    )
    if (shouldFree) {
        addStatement("if ($resRawName != %T.NULL) xrossFreeStringHandle.invokeExact($resRawName)", MEMORY_SEGMENT)
    }
    return this
}

fun CodeBlock.Builder.addResultVariantResolution(
    type: XrossType,
    ptrName: String,
    targetTypeName: TypeName,
    selfType: ClassName,
    basePackage: String,
    dropHandleName: String = "dropHandle",
) {
    val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

    val needsRun = type is XrossType.Object || type is XrossType.RustString
    if (needsRun) beginControlFlow("run")

    when (type) {
        is XrossType.Object -> {
            val (sizeExpr, dropExpr, fromPointerExpr) = GeneratorUtils.compareExprs(targetTypeName, selfType, dropHandleName)
            addResourceConstruction(type, ptrName, sizeExpr, fromPointerExpr, dropExpr, flagType)
        }
        is XrossType.RustString -> {
            addRustStringResolution(ptrName)
            addStatement("str")
        }
        is XrossType.F32 -> {
            val code = CodeBlock.of("%T.fromBits(%L.address().toInt())", Float::class, ptrName)
            if (needsRun) addStatement("%L", code) else add("%L", code)
        }
        is XrossType.F64 -> {
            val code = CodeBlock.of("%T.fromBits(%L.address())", Double::class, ptrName)
            if (needsRun) addStatement("%L", code) else add("%L", code)
        }
        is XrossType.Bool -> {
            val code = CodeBlock.of("%L.address() != 0L", ptrName)
            if (needsRun) addStatement("%L", code) else add("%L", code)
        }
        else -> {
            // For other primitives, they are passed as address (value-in-pointer)
            val kType = type.kotlinType
            val code = if (type.kotlinSize <= 4) {
                if (kType == INT) {
                    CodeBlock.of("%L.address().toInt()", ptrName)
                } else {
                    CodeBlock.of("%L.address().toInt() as %T", ptrName, kType)
                }
            } else {
                if (kType == LONG) {
                    CodeBlock.of("%L.address()", ptrName)
                } else {
                    CodeBlock.of("%L.address() as %T", ptrName, kType)
                }
            }
            if (needsRun) addStatement("%L", code) else add("%L", code)
        }
    }
    if (needsRun) endControlFlow()
    this.add("\n")
}

fun CodeBlock.Builder.addArgumentPreparation(
    type: XrossType,
    name: String,
    callArgs: MutableList<CodeBlock>,
    checkObjectValidity: Boolean = false,
) {
    when (type) {
        is XrossType.RustString -> {
            addStatement("val ${name}Memory = arena.allocateFrom($name)")
            callArgs.add(CodeBlock.of("${name}Memory"))
        }

        is XrossType.Object -> {
            if (checkObjectValidity) {
                beginControlFlow(
                    "if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)",
                    MEMORY_SEGMENT,
                )
                addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Arg invalid")
                endControlFlow()
            }
            callArgs.add(CodeBlock.of("$name.segment"))
        }

        is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
        is XrossType.Optional -> {
            addStatement(
                "val ${name}Memory = if ($name == null) %T.NULL else %L",
                MEMORY_SEGMENT,
                GeneratorUtils.generateAllocMsg(type.inner, name),
            )
            callArgs.add(CodeBlock.of("${name}Memory"))
        }

        is XrossType.Result -> {
            addResultAllocation(type, name, "${name}Memory")
            callArgs.add(CodeBlock.of("${name}Memory"))
        }

        else -> callArgs.add(CodeBlock.of("%L", name))
    }
}
