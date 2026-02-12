package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment
import java.lang.ref.WeakReference

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, basePackage: String) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val kType = if (field.ty is XrossType.Object) {
                GeneratorUtils.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            var backingFieldName: String? = null
            if (field.ty is XrossType.Object) {
                backingFieldName = "_$baseName"
                val weakRefType = ClassName("java.lang.ref", "WeakReference").parameterizedBy(kType)
                val backingProp = PropertySpec.builder(backingFieldName, weakRefType.copy(nullable = true))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("null")
                    .build()
                classBuilder.addProperty(backingProp)
            }

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, kType)
            } else {
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(buildGetter(field, vhName, kType, backingFieldName, selfType, basePackage))

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, kType, backingFieldName, selfType, basePackage))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val isSelf = kType == selfType
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        when (field.ty) {
            is XrossType.Object -> {
                readCodeBuilder.addStatement("val cached = this.$backingFieldName?.get()")
                readCodeBuilder.beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                readCodeBuilder.addStatement("res = cached")
                readCodeBuilder.nextControlFlow("else")

                val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                val isOwned = field.ty.ownership == XrossType.Ownership.Owned
                val ffiHelpers = ClassName("$basePackage.xross.runtime", "FfiHelpers")
                val vh=if (isOwned) "null" else vhName
                readCodeBuilder.addStatement(
                    "val resSeg = %T.resolveFieldSegment(this.segment, $vh, OFFSET_$baseName, $sizeExpr, $isOwned)",
                    ffiHelpers,
                )

                readCodeBuilder.addStatement(
                    "res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))",
                    fromPointerExpr,
                    aliveFlagType,
                )
                readCodeBuilder.addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                readCodeBuilder.endControlFlow()
            }

            is XrossType.Optional -> {
                readCodeBuilder.addStatement(
                    "val resRaw = ${baseName}OptGetHandle.invokeExact(this.segment) as %T",
                    MemorySegment::class,
                )
                readCodeBuilder.beginControlFlow("if (resRaw == %T.NULL)", MemorySegment::class)
                readCodeBuilder.addStatement("res = null")
                readCodeBuilder.nextControlFlow("else")

                val inner = field.ty.inner
                val isSelfOk = if (inner is XrossType.Object) GeneratorUtils.getClassName(inner.signature, basePackage) == selfType else false

                when (inner) {
                    is XrossType.Object -> {
                        val innerType = GeneratorUtils.getClassName(inner.signature, basePackage)
                        val sizeExpr = if (isSelfOk) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", innerType)
                        val dropExpr = if (isSelfOk) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", innerType)
                        val fromPointerExpr = if (isSelfOk) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", innerType)

                        readCodeBuilder.addStatement("val retAutoArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val retOwnerArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val flag = %T(true)", aliveFlagType)
                        readCodeBuilder.addStatement(
                            "val reinterpreted = resRaw.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
                            sizeExpr,
                            dropExpr,
                        )
                        readCodeBuilder.addStatement("res = %L(reinterpreted, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", fromPointerExpr)
                    }

                    is XrossType.RustString -> {
                        readCodeBuilder.addStatement("res = resRaw.reinterpret(%T.MAX_VALUE).getString(0)", Long::class)
                        readCodeBuilder.addStatement("xrossFreeStringHandle.invokeExact(resRaw)")
                    }

                    else -> {
                        readCodeBuilder.addStatement("res = resRaw.get(%M, 0)", inner.layoutMember)
                        readCodeBuilder.addStatement("dropHandle.invokeExact(resRaw)")
                    }
                }
                readCodeBuilder.endControlFlow()
            }

            is XrossType.Result -> {
                readCodeBuilder.addStatement(
                    "val resRaw = ${baseName}ResGetHandle.invokeExact(this.segment) as %T",
                    MemorySegment::class,
                )
                readCodeBuilder.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", MemberName("java.lang.foreign.ValueLayout", "JAVA_BYTE"))
                readCodeBuilder.addStatement("val ptr = resRaw.get(%M, 8L)", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"))

                readCodeBuilder.beginControlFlow("if (isOk)")
                readCodeBuilder.beginControlFlow("val okVal = run")
                val okTy = field.ty.ok
                val okKType = if (okTy is XrossType.Object) GeneratorUtils.getClassName(okTy.signature, basePackage) else okTy.kotlinType
                val isOkSelf = okKType == selfType

                when (okTy) {
                    is XrossType.Object -> {
                        val okSizeExpr = if (isOkSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", okKType)
                        val okDropExpr = if (isOkSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", okKType)
                        val okFromPointerExpr = if (isOkSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", okKType)

                        readCodeBuilder.addStatement("val retAutoArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val retOwnerArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val flag = %T(true)", aliveFlagType)
                        readCodeBuilder.addStatement(
                            "val reinterpreted = ptr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
                            okSizeExpr,
                            okDropExpr,
                        )
                        readCodeBuilder.addStatement("%L(reinterpreted, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", okFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        GeneratorUtils.addRustStringResolution(readCodeBuilder, "ptr", "s", isAssignment = false)
                        readCodeBuilder.addStatement("s")
                    }
                    else -> {
                        readCodeBuilder.addStatement("val v = ptr.get(%M, 0)", okTy.layoutMember)
                        readCodeBuilder.addStatement("dropHandle.invokeExact(ptr)")
                        readCodeBuilder.addStatement("v")
                    }
                }
                readCodeBuilder.endControlFlow()
                readCodeBuilder.addStatement("res = Result.success(okVal) as %T", kType)

                readCodeBuilder.nextControlFlow("else")

                readCodeBuilder.beginControlFlow("val errVal = run")
                val errTy = field.ty.err
                val errKType = if (errTy is XrossType.Object) GeneratorUtils.getClassName(errTy.signature, basePackage) else errTy.kotlinType
                val isErrSelf = errKType == selfType

                when (errTy) {
                    is XrossType.Object -> {
                        val errSizeExpr = if (isErrSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", errKType)
                        val errDropExpr = if (isErrSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", errKType)
                        val errFromPointerExpr = if (isErrSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", errKType)

                        readCodeBuilder.addStatement("val retAutoArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val retOwnerArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val flag = %T(true)", aliveFlagType)
                        readCodeBuilder.addStatement(
                            "val reinterpreted = ptr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
                            errSizeExpr,
                            errDropExpr,
                        )
                        readCodeBuilder.addStatement("%L(reinterpreted, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", errFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        GeneratorUtils.addRustStringResolution(readCodeBuilder, "ptr", "s", isAssignment = false)
                        readCodeBuilder.addStatement("s")
                    }
                    else -> {
                        readCodeBuilder.addStatement("val v = ptr.get(%M, 0)", errTy.layoutMember)
                        readCodeBuilder.addStatement("dropHandle.invokeExact(ptr)")
                        readCodeBuilder.addStatement("v")
                    }
                }
                readCodeBuilder.endControlFlow()
                readCodeBuilder.addStatement("res = Result.failure<%T>(%T(errVal)) as %T", okKType, ClassName("$basePackage.xross.runtime", "XrossException"), kType)
                readCodeBuilder.endControlFlow()
            }

            is XrossType.Bool -> readCodeBuilder.addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                val callExpr = "${baseName}StrGetHandle.invokeExact(this.segment)"
                GeneratorUtils.addRustStringResolution(readCodeBuilder, callExpr, "res", isAssignment = true)
            }

            else -> readCodeBuilder.addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
        }

        return GeneratorUtils.buildOptimisticReadGetter(kType, readCodeBuilder.build())
    }
    private fun buildSetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement(
            "if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)",
            MemorySegment::class,
            NullPointerException::class,
            "Attempted to set field '${field.name}' on a NULL or invalid native object",
        )

        val isSelf = kType == selfType
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (field.ty) {
            is XrossType.RustString -> {
                writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                writeCodeBuilder.addStatement("val allocated = arena.allocateFrom(v)")
                writeCodeBuilder.addStatement("${baseName}StrSetHandle.invokeExact(this.segment, allocated) as Unit")
                writeCodeBuilder.endControlFlow()
            }

            is XrossType.Optional -> {
                writeCodeBuilder.beginControlFlow("if (v == null)")
                writeCodeBuilder.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, %T.NULL) as Unit", MemorySegment::class)
                writeCodeBuilder.nextControlFlow("else")
                when (val inner = field.ty.inner) {
                    is XrossType.RustString -> {
                        writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                        writeCodeBuilder.addStatement("val allocated = arena.allocateFrom(v)")
                        writeCodeBuilder.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                        writeCodeBuilder.endControlFlow()
                    }

                    is XrossType.Object -> {
                        writeCodeBuilder.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, v.segment) as Unit")
                    }

                    else -> {
                        writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                        writeCodeBuilder.addStatement("val allocated = arena.allocate(%M, v)", inner.layoutMember)
                        writeCodeBuilder.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                        writeCodeBuilder.endControlFlow()
                    }
                }
                writeCodeBuilder.endControlFlow()
            }

            is XrossType.Result -> {
                val okTy = field.ty.ok
                writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                writeCodeBuilder.addStatement("val xrossRes = arena.allocate(java.lang.foreign.MemoryLayout.structLayout(java.lang.foreign.ValueLayout.JAVA_BYTE.withName(\"isOk\"), java.lang.foreign.MemoryLayout.paddingLayout(7), java.lang.foreign.ValueLayout.ADDRESS.withName(\"ptr\")))")
                writeCodeBuilder.beginControlFlow("if (v.isSuccess)")
                writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, 1.toByte())")
                val okVal = "v.getOrThrow()"
                when (okTy) {
                    is XrossType.Object -> {
                        writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.ADDRESS, 8L, $okVal.segment)")
                    }

                    is XrossType.RustString -> {
                        writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.ADDRESS, 8L, arena.allocateFrom($okVal))")
                    }

                    else -> {
                        writeCodeBuilder.addStatement("val allocated = arena.allocate(%M, $okVal)", okTy.layoutMember)
                        writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.ADDRESS, 8L, allocated)")
                    }
                }
                writeCodeBuilder.nextControlFlow("else")
                writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, 0.toByte())")
                writeCodeBuilder.addStatement("val err = v.exceptionOrNull()!!")
                writeCodeBuilder.beginControlFlow("if (err is %T)", ClassName("$basePackage.xross.runtime", "XrossException"))
                writeCodeBuilder.addStatement("// TODO: Pass error value back if needed")
                writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.ADDRESS, 8L, java.lang.foreign.MemorySegment.NULL)")
                writeCodeBuilder.nextControlFlow("else")
                writeCodeBuilder.addStatement("xrossRes.set(java.lang.foreign.ValueLayout.ADDRESS, 8L, java.lang.foreign.MemorySegment.NULL)")
                writeCodeBuilder.endControlFlow()
                writeCodeBuilder.endControlFlow()
                writeCodeBuilder.addStatement("${baseName}ResSetHandle.invokeExact(this.segment, xrossRes) as Unit")
                writeCodeBuilder.endControlFlow()
            }

            is XrossType.Bool -> writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement(
                    "if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Cannot set field '${field.name}' with a NULL or invalid native reference",
                )

                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    writeCodeBuilder.addStatement(
                        "this.segment.asSlice($offsetName, $sizeExpr).copyFrom(v.segment)",
                        *(if (isSelf) emptyArray() else arrayOf(kType)),
                    )
                } else {
                    writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                }

                if (backingFieldName != null) {
                    writeCodeBuilder.addStatement("this.$backingFieldName = null")
                }
            }

            else -> writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, v)")
        }

        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
            """
        val stamp = this.sl.writeLock()
        try { %L } finally { this.sl.unlockWrite(stamp) }
            """.trimIndent(),
            writeCodeBuilder.build(),
        ).build()
    }
    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        baseName: String,
        escapedName: String,
        vhName: String,
        kType: TypeName,
    ) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val offsetName = "OFFSET_$baseName"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return $vhName.getVolatile(this@${className(classBuilder)}.segment, $offsetName) as %T", kType)
                            .build(),
                    ).build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                    .beginControlFlow("while (true)")
                    .beginControlFlow("try")
                    .addStatement("val current = value")
                    .addStatement("val next = block(current)")
                    .beginControlFlow("if ($vhName.compareAndSet(this@${className(classBuilder)}.segment, $offsetName, current, next))")
                    .addStatement("return next")
                    .endControlFlow()
                    .nextControlFlow("catch (e: %T)", Throwable::class)
                    .addStatement("throw e")
                    .endControlFlow()
                    .endControlFlow().build(),
            )
            .build()
        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName)).initializer("%L()", innerClassName).build(),
        )
    }

    private fun className(builder: TypeSpec.Builder): String = builder.build().name!!
}
