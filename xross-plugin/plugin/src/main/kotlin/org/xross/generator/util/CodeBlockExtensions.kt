package org.xross.generator.util

import com.squareup.kotlinpoet.*
import org.xross.generator.util.FFMConstants.MEMORY_SEGMENT
import org.xross.structures.XrossType

fun CodeBlock.Builder.addResourceConstruction(
    inner: XrossType,
    resRaw: String,
    sizeExpr: CodeBlock,
    fromPointerExpr: CodeBlock,
    dropExpr: CodeBlock,
    flagType: ClassName,
) {
    if (inner.isOwned) {
        // オブジェクトごとに Arena を作らず、Cleaner/close() で直接 drop を呼ぶ
        addStatement("val res = %L.reinterpret(%L)", resRaw, sizeExpr)

        // 所有権を持つオブジェクトは parent = null
        addStatement("val resObj = %L(res, parent = null, isPersistent = false)", fromPointerExpr)
        // Cleaner に drop を登録
        addStatement("resObj.registerNativeCleaner(%L)", dropExpr)
        addStatement("resObj")
    } else {
        // 借用: this を parent として渡す
        addStatement("val reinterpreted = %L.reinterpret(%L)", resRaw, sizeExpr)
        addStatement(
            "%L(reinterpreted, parent = this, isPersistent = false)",
            fromPointerExpr,
        )
    }
}

fun CodeBlock.Builder.addArenaAndFlag(
    basePackage: String,
    isPersistent: Boolean = false,
    externalArena: CodeBlock? = null,
): CodeBlock.Builder {
    // Arena は引数準備などの一時的な利用に留める (Arena.ofAuto() または externalArena)
    if (externalArena != null) {
        addStatement("val newOwnerArena = %L ?: java.lang.foreign.Arena.ofAuto()", externalArena)
        // flag は単純な boolean (isPersistent) として扱う
        addStatement("val isPersistentVal = %L || %L != null", isPersistent, externalArena)
    } else {
        addStatement("val newOwnerArena = java.lang.foreign.Arena.ofAuto()")
        addStatement("val isPersistentVal = %L", isPersistent)
    }
    return this
}

fun CodeBlock.Builder.addFactoryBody(
    basePackage: String,
    handleCall: CodeBlock,
    structSizeExpr: CodeBlock,
    dropHandleExpr: CodeBlock,
    isPersistent: Boolean = false,
    handleMode: org.xross.structures.HandleMode = org.xross.structures.HandleMode.Normal,
    externalArena: CodeBlock? = null,
    defineArenaAndFlag: Boolean = true,
): CodeBlock.Builder {
    val runtimePkg = "$basePackage.xross.runtime"
    val xrossRuntime = ClassName(runtimePkg, "XrossRuntime")
    val memorySegment = ClassName("java.lang.foreign", "MemorySegment")

    if (defineArenaAndFlag) {
        addArenaAndFlag(basePackage, isPersistent, externalArena)
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

    addStatement(
        "val res = resRaw.reinterpret(%L)",
        structSizeExpr,
    )

    return this
}

fun CodeBlock.Builder.addResultAllocation(
    ty: XrossType.Result,
    valueName: String,
    targetMemoryName: String,
    arenaName: String = "java.lang.foreign.Arena.ofAuto()",
): CodeBlock.Builder {
    addStatement("val $targetMemoryName = $arenaName.allocate(%L)", FFMConstants.XROSS_RESULT_LAYOUT_CODE)
    beginControlFlow("if ($valueName.isSuccess)")
    addStatement("$targetMemoryName.set(%M, 0L, 1.toByte())", FFMConstants.JAVA_BYTE)
    addStatement("$targetMemoryName.set(%M, 8L, %L)", FFMConstants.ADDRESS, GeneratorUtils.generateAllocMsg(ty.ok, "$valueName.getOrNull()!!", arenaName))
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
    arenaName: String = "java.lang.foreign.Arena.ofAuto()",
): CodeBlock.Builder {
    val resRawName = if (call is String && call.endsWith("RawInternal")) call else "${resultVar}RawInternal"
    if (!(call is String && call == resRawName)) {
        when {
            call == "it" -> addStatement("val $resRawName = %L", call)
            call is String && (call.endsWith("Raw") || call.endsWith("Segment") || call == "resRaw") ->
                addStatement("val $resRawName = %L", call)
            else -> {
                if (call is CodeBlock || (call is String && call.contains("("))) {
                    addStatement("val $resRawName = %L as %T", call, MEMORY_SEGMENT)
                } else {
                    addStatement("val $resRawName = %L", call)
                }
            }
        }
    }

    // Convert to String helper (Inlined to avoid XrossString object)
    val decl = if (isAssignment) "" else "val "
    addStatement("val ${resultVar}Ptr = $resRawName.get(java.lang.foreign.ValueLayout.ADDRESS, 0L)")
    addStatement("val ${resultVar}Len = $resRawName.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8L)")
    beginControlFlow("%L%L = if (${resultVar}Ptr == %T.NULL || ${resultVar}Len == 0L)", decl, resultVar, MEMORY_SEGMENT)
    addStatement("%S", "")
    nextControlFlow("else")
    addStatement("java.nio.charset.StandardCharsets.UTF_8.decode(${resultVar}Ptr.reinterpret(${resultVar}Len).asByteBuffer()).toString()")
    endControlFlow()

    if (shouldFree) {
        addStatement("if ($resRawName != %T.NULL) xrossFreeStringHandle.invoke($resRawName)", MEMORY_SEGMENT)
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
    when (type) {
        is XrossType.Object -> {
            beginControlFlow("run")
            val (sizeExpr, dropExpr, fromPointerExpr) = GeneratorUtils.compareExprs(targetTypeName, selfType, dropHandleName)
            addResourceConstruction(type, ptrName, sizeExpr, fromPointerExpr, dropExpr, ClassName("", "UNUSED"))
            endControlFlow()
        }
        is XrossType.RustString -> {
            beginControlFlow("run")
            addRustStringResolution(ptrName, basePackage = basePackage)
            addStatement("str")
            endControlFlow()
        }
        is XrossType.F32 -> {
            add("%T.fromBits(%L.address().toInt())", Float::class, ptrName)
        }
        is XrossType.F64 -> {
            add("%T.fromBits(%L.address())", Double::class, ptrName)
        }
        is XrossType.Bool -> {
            add("%L.address() != 0L", ptrName)
        }
        else -> {
            val kType = type.kotlinType
            if (type.kotlinSize <= 4) {
                if (kType == INT) {
                    add("%L.address().toInt()", ptrName)
                } else {
                    add("%L.address().toInt() as %T", ptrName, kType)
                }
            } else {
                if (kType == LONG) {
                    add("%L.address()", ptrName)
                } else {
                    add("%L.address() as %T", ptrName, kType)
                }
            }
        }
    }
    this.add("\n")
}

fun CodeBlock.Builder.addOptionalResolution(
    inner: XrossType,
    resRaw: String,
    selfType: ClassName,
    basePackage: String,
    dropHandleName: String = "dropHandle",
) {
    beginControlFlow("run")
    beginControlFlow("if ($resRaw == %T.NULL)", MEMORY_SEGMENT)
        .addStatement("null")
    nextControlFlow("else")
    val innerType = GeneratorUtils.resolveReturnType(inner, basePackage)
    addResultVariantResolution(inner, resRaw, innerType, selfType, basePackage, dropHandleName)
    endControlFlow()
    endControlFlow()
}

fun CodeBlock.Builder.addResultResolution(
    ty: XrossType.Result,
    resRaw: String,
    selfType: ClassName,
    basePackage: String,
    dropHandleName: String = "dropHandle",
) {
    beginControlFlow("run")
    val runtimePkg = "$basePackage.xross.runtime"
    addStatement("val resRawSeg = $resRaw")
    addStatement("val isOk = resRawSeg.get(%M, 0L) != (0).toByte()", FFMConstants.JAVA_BYTE)
    addStatement("val ptr = resRawSeg.get(%M, 8L)", FFMConstants.ADDRESS)

    beginControlFlow("if (isOk)")
    add("val okVal = ")
    addResultVariantResolution(ty.ok, "ptr", GeneratorUtils.resolveReturnType(ty.ok, basePackage), selfType, basePackage, dropHandleName)
    addStatement("Result.success(okVal)")

    nextControlFlow("else")
    add("val errVal = ")
    addResultVariantResolution(ty.err, "ptr", GeneratorUtils.resolveReturnType(ty.err, basePackage), selfType, basePackage, dropHandleName)
    addStatement("Result.failure(%T(errVal))", ClassName(runtimePkg, "XrossException"))
    endControlFlow()
    endControlFlow()
}

fun CodeBlock.Builder.addArgumentPreparation(
    type: XrossType,
    name: String,
    callArgs: MutableList<CodeBlock>,
    checkObjectValidity: Boolean = false,
    basePackage: String = "org.example",
    handleMode: org.xross.structures.HandleMode = org.xross.structures.HandleMode.Normal,
    arenaName: String = "java.lang.foreign.Arena.ofAuto()",
) {
    val runtimePkg = "$basePackage.xross.runtime"
    val xrossRuntime = ClassName(runtimePkg, "XrossRuntime")

    when (type) {
        is XrossType.RustString -> {
            // Prefer true zero-copy string path regardless of handle mode.
            // If reflection-based extraction is unavailable, fallback to UTF-8 copy.
            addStatement("val ${name}Value = %T.getStringValue($name)", xrossRuntime)
            addStatement("val ${name}Coder = %T.getStringCoder($name)", xrossRuntime)
            addStatement("var ${name}FinalSeg: %T = %T.NULL", MEMORY_SEGMENT, MEMORY_SEGMENT)
            addStatement("var ${name}FinalLen: Long = 0L")
            addStatement("var ${name}FinalEnc: Byte = 0")

            beginControlFlow("if (${name}Value != null)")
            addStatement("${name}FinalSeg = %T.ofArray(${name}Value)", MEMORY_SEGMENT)
            addStatement("${name}FinalLen = ${name}Value.size.toLong()")
            addStatement("${name}FinalEnc = ${name}Coder")
            nextControlFlow("else")
            addStatement("val ${name}Buf = $arenaName.allocateFrom($name)")
            addStatement("${name}FinalSeg = ${name}Buf")
            addStatement("${name}FinalLen = ${name}Buf.byteSize()")
            addStatement("${name}FinalEnc = 0")
            endControlFlow()

            callArgs.add(CodeBlock.of("${name}FinalSeg"))
            callArgs.add(CodeBlock.of("${name}FinalLen"))
            callArgs.add(CodeBlock.of("${name}FinalEnc"))
        }

        is XrossType.Object -> {
            if (checkObjectValidity) {
                beginControlFlow(
                    "if ($name.segment == %T.NULL || !$name.isValid)",
                    MEMORY_SEGMENT,
                )
                addStatement("throw %T(%S + $name.segment + %S + $name.isValid)", NullPointerException::class.asTypeName(), "Arg invalid: segment=", ", isValid=")
                endControlFlow()
            }
            callArgs.add(CodeBlock.of("$name.segment"))
        }

        is XrossType.Bool -> callArgs.add(CodeBlock.of("if ($name) 1.toByte() else 0.toByte()"))
        is XrossType.Optional -> {
            addStatement(
                "val ${name}Memory = if ($name == null) %T.NULL else %L",
                MEMORY_SEGMENT,
                GeneratorUtils.generateAllocMsg(type.inner, name, arenaName),
            )
            callArgs.add(CodeBlock.of("${name}Memory"))
        }

        is XrossType.Result -> {
            addResultAllocation(type, name, "${name}Memory", arenaName)
            callArgs.add(CodeBlock.of("${name}Memory"))
        }

        is XrossType.Slice, is XrossType.Vec -> {
            val isNullable = type is XrossType.Optional // This might need refinement depending on how nullability is tracked
            // Actually, we can check if the type name is nullable
            val kotlinType = GeneratorUtils.resolveReturnType(type, basePackage)
            if (kotlinType.isNullable) {
                addStatement("val ${name}Seg = if ($name == null) %T.NULL else %T.ofArray($name)", MEMORY_SEGMENT, MEMORY_SEGMENT)
            } else {
                addStatement("val ${name}Seg = %T.ofArray($name)", MEMORY_SEGMENT)
            }
            callArgs.add(CodeBlock.of("${name}Seg"))
            callArgs.add(CodeBlock.of("$name.size.toLong()"))
        }

        else -> callArgs.add(CodeBlock.of("%L", name))
    }
}
