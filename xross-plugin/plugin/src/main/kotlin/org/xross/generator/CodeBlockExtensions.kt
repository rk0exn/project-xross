package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.FFMConstants.MEMORY_SEGMENT
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
        addStatement("if ($resRawName != %T.NULL) Companion.xrossFreeStringHandle.invokeExact($resRawName)", MEMORY_SEGMENT)
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

    beginControlFlow("run")
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
            addStatement("%T.fromBits(%L.address().toInt())", Float::class, ptrName)
        }
        is XrossType.F64 -> {
            addStatement("%T.fromBits(%L.address())", Double::class, ptrName)
        }
        is XrossType.Bool -> {
            addStatement("%L.address() != 0L", ptrName)
        }
        else -> {
            // For other primitives, they are passed as address (value-in-pointer)
            if (type.kotlinSize <= 4) {
                addStatement("%L.address().toInt() as %T", ptrName, type.kotlinType)
            } else {
                addStatement("%L.address() as %T", ptrName, type.kotlinType)
            }
        }
    }
    endControlFlow()
}
