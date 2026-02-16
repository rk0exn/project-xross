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
    val runtimePkg = flagType.packageName
    val xrossRuntime = ClassName(runtimePkg, "XrossRuntime")

    if (inner.isOwned) {
        // 1オブジェクトにつき Arena 1つのみ。
        addStatement("val retOwnerArena = %T.ofSmart()", xrossRuntime)
        addStatement("val flag = %T(initial = true, isPersistent = false)", flagType)

        addStatement("val dh = %L", dropExpr)
        addStatement(
            "val res = %L.reinterpret(%L, retOwnerArena) { s -> %T.invokeDrop(dh, s) }",
            resRaw,
            sizeExpr,
            xrossRuntime,
        )

        // オブジェクト作成 (XrossNativeObject の init で Cleaner に登録される)
        addStatement("val resObj = %L(res, retOwnerArena, flag)", fromPointerExpr)
        addStatement("resObj")
    } else {
        // 借用
        addStatement(
            "%L(%L, %T.ofAuto(), sharedFlag = %T(true, this.aliveFlag))",
            fromPointerExpr,
            resRaw,
            java.lang.foreign.Arena::class,
            flagType,
        )
    }
}

fun CodeBlock.Builder.addFactoryBody(
    basePackage: String,
    handleCall: CodeBlock,
    structSizeExpr: CodeBlock,
    dropHandleExpr: CodeBlock,
    isPersistent: Boolean = false,
    handleMode: org.xross.structures.HandleMode = org.xross.structures.HandleMode.Normal,
    externalArena: CodeBlock? = null,
): CodeBlock.Builder {
    val runtimePkg = "$basePackage.xross.runtime"
    val aliveFlagType = ClassName(runtimePkg, "AliveFlag")
    val xrossRuntime = ClassName(runtimePkg, "XrossRuntime")
    val memorySegment = ClassName("java.lang.foreign", "MemorySegment")

    if (externalArena != null) {
        addStatement("val newOwnerArena = %L ?: %T.ofSmart()", externalArena, xrossRuntime)
        addStatement("val flag = %T(initial = true, isPersistent = %L || %L != null)", aliveFlagType, isPersistent, externalArena)
    } else {
        addStatement("val newOwnerArena = %T.ofSmart()", xrossRuntime)
        addStatement("val flag = %T(initial = true, isPersistent = %L)", aliveFlagType, isPersistent)
    }

    if (handleMode is org.xross.structures.HandleMode.Panicable) {
        addStatement("val resRawObj = %L as %T", handleCall, memorySegment)
        addStatement("val isOk = resRawObj.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()")
        addStatement("val resRaw = resRawObj.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)")
        beginControlFlow("if (!isOk)")
        addRustStringResolution("resRaw", "errVal", basePackage = basePackage)
        addStatement("throw %T(errVal)", ClassName(runtimePkg, "XrossException"))
        endControlFlow()
    } else {
        addStatement("val resRaw = %L as %T", handleCall, memorySegment)
    }

    addStatement(
        "if (resRaw == %T.NULL) throw %T(%S)",
        memorySegment,
        RuntimeException::class.asTypeName(),
        "Fail",
    )

    addStatement("val dh = %L", dropHandleExpr)
    addStatement(
        "val res = resRaw.reinterpret(%L, newOwnerArena) { s -> %T.invokeDrop(dh, s) }",
        structSizeExpr,
        xrossRuntime,
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

fun CodeBlock.Builder.addRustStringResolution(
    call: Any,
    resultVar: String = "str",
    isAssignment: Boolean = false,
    shouldFree: Boolean = true,
    basePackage: String = "org.example",
): CodeBlock.Builder {
    val resRawName = if (call is String && call.endsWith("RawInternal")) call else "${resultVar}RawInternal"
    if (!(call is String && call == resRawName)) {
        if (call == "it") {
            addStatement("val $resRawName = %L", call)
        } else {
            addStatement("val $resRawName = %L as %T", call, MEMORY_SEGMENT)
        }
    }

    // Check if it's a pointer to XrossString or XrossString itself
    addStatement("val xsSeg = if ($resRawName.byteSize() < 24) $resRawName.reinterpret(24) else $resRawName")

    // Convert to XrossString helper
    val decl = if (isAssignment) "" else "val "
    addStatement(
        "%L%L = if ($resRawName == %T.NULL) \"\" else %T(xsSeg).toString()",
        decl,
        resultVar,
        MEMORY_SEGMENT,
        ClassName("$basePackage.xross.runtime", "XrossString"),
    )
    if (shouldFree) {
        addStatement("if ($resRawName != %T.NULL) xrossFreeStringHandle.invoke(xsSeg)", MEMORY_SEGMENT)
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
            addRustStringResolution(ptrName, basePackage = basePackage)
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
    basePackage: String = "org.example",
    handleMode: org.xross.structures.HandleMode = org.xross.structures.HandleMode.Normal,
) {
    val runtimePkg = "$basePackage.xross.runtime"
    val xrossRuntime = ClassName(runtimePkg, "XrossRuntime")

    when (type) {
        is XrossType.RustString -> {
            val isHeapCritical = handleMode is org.xross.structures.HandleMode.Critical && handleMode.allowHeapAccess

            if (isHeapCritical) {
                // True Zero-Copy: Pass heap segment directly
                addStatement("val ${name}Value = %T.getStringValue($name)", xrossRuntime)
                addStatement("val ${name}Coder = %T.getStringCoder($name)", xrossRuntime)
                addStatement("var ${name}FinalSeg: %T = %T.NULL", MEMORY_SEGMENT, MEMORY_SEGMENT)
                addStatement("var ${name}FinalLen: Long = 0L")
                addStatement("var ${name}FinalEnc: Byte = 0")

                beginControlFlow("if (${name}Value != null)")
                addStatement("${name}FinalSeg = %T.ofArray(${name}Value)", MEMORY_SEGMENT)
                addStatement("${name}FinalLen = $name.length.toLong()")
                addStatement("${name}FinalEnc = ${name}Coder")
                nextControlFlow("else")
                // Fallback if reflection fails
                addStatement("val ${name}Buf = arena.allocateFrom($name)")
                addStatement("${name}FinalSeg = ${name}Buf")
                addStatement("${name}FinalLen = ${name}Buf.byteSize()")
                addStatement("${name}FinalEnc = 0")
                endControlFlow()

                callArgs.add(CodeBlock.of("${name}FinalSeg"))
                callArgs.add(CodeBlock.of("${name}FinalLen"))
                callArgs.add(CodeBlock.of("${name}FinalEnc"))
            } else {
                // Optimized Copy
                addStatement("val ${name}Buffer = arena.allocateFrom($name)")
                callArgs.add(CodeBlock.of("${name}Buffer"))
                callArgs.add(CodeBlock.of("$name.length.toLong()"))
                callArgs.add(CodeBlock.of("%T.getStringCoder($name)", xrossRuntime))
            }
        }

        is XrossType.Object -> {
            if (checkObjectValidity) {
                beginControlFlow(
                    "if ($name.segment == %T.NULL || !$name.aliveFlag.isValid)",
                    MEMORY_SEGMENT,
                )
                addStatement("throw %T(%S + $name.segment + %S + $name.aliveFlag.isValid)", NullPointerException::class.asTypeName(), "Arg invalid: segment=", ", isValid=")
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
