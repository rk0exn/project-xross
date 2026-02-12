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
                val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.Companion.fromPointer", kType)
                val isOwned = field.ty.ownership == XrossType.Ownership.Owned

                readCodeBuilder.addStatement(
                    "val resSeg = resolveFieldSegment(this.segment, if (%L) null else $vhName, OFFSET_$baseName, %L, %L)",
                    isOwned,
                    sizeExpr,
                    isOwned,
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
                    "val resRaw = Companion.${baseName}OptGetHandle.invokeExact(this.segment) as %T",
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
                        val fromPointerExpr = if (isSelfOk) CodeBlock.of("fromPointer") else CodeBlock.of("%T.Companion.fromPointer", innerType)

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
                        readCodeBuilder.addStatement("Companion.xrossFreeStringHandle.invokeExact(resRaw)")
                    }

                    else -> {
                        readCodeBuilder.addStatement("res = resRaw.get(%M, 0)", inner.layoutMember)
                        readCodeBuilder.addStatement("Companion.dropHandle.invokeExact(resRaw)")
                    }
                }
                readCodeBuilder.endControlFlow()
            }

            is XrossType.Result -> {
                readCodeBuilder.addStatement(
                    "val resRaw = Companion.${baseName}ResGetHandle.invokeExact(this.segment) as %T",
                    MemorySegment::class,
                )
                readCodeBuilder.addStatement("val okPtr = resRaw.get(%M, 0L)", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"))
                readCodeBuilder.addStatement("val errPtr = resRaw.get(%M, %T.ADDRESS.byteSize())", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"), ClassName("java.lang.foreign", "ValueLayout"))

                readCodeBuilder.beginControlFlow("if (okPtr != %T.NULL)", MemorySegment::class)
                readCodeBuilder.beginControlFlow("val okVal = run")
                val okTy = field.ty.ok
                val okKType = if (okTy is XrossType.Object) GeneratorUtils.getClassName(okTy.signature, basePackage) else okTy.kotlinType
                val isOkSelf = okKType == selfType

                when (okTy) {
                    is XrossType.Object -> {
                        val okSizeExpr = if (isOkSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", okKType)
                        val okDropExpr = if (isOkSelf) CodeBlock.of("dropHandle") else CodeBlock.of("%T.dropHandle", okKType)
                        val okFromPointerExpr = if (isOkSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.Companion.fromPointer", okKType)

                        readCodeBuilder.addStatement("val retAutoArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val retOwnerArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val flag = %T(true)", aliveFlagType)
                        readCodeBuilder.addStatement(
                            "val reinterpreted = okPtr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
                            okSizeExpr,
                            okDropExpr,
                        )
                        readCodeBuilder.addStatement("%L(reinterpreted, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", okFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        readCodeBuilder.addStatement("val s = okPtr.reinterpret(%T.MAX_VALUE).getString(0)", Long::class)
                        readCodeBuilder.addStatement("Companion.xrossFreeStringHandle.invokeExact(okPtr)")
                        readCodeBuilder.addStatement("s")
                    }
                    else -> {
                        readCodeBuilder.addStatement("val v = okPtr.get(%M, 0)", okTy.layoutMember)
                        readCodeBuilder.addStatement("Companion.dropHandle.invokeExact(okPtr)")
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
                        val errFromPointerExpr = if (isErrSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.Companion.fromPointer", errKType)

                        readCodeBuilder.addStatement("val retAutoArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val retOwnerArena = Arena.ofAuto()")
                        readCodeBuilder.addStatement("val flag = %T(true)", aliveFlagType)
                        readCodeBuilder.addStatement(
                            "val reinterpreted = errPtr.reinterpret(%L, retAutoArena) { s -> if (flag.tryInvalidate()) { %L.invokeExact(s) } }",
                            errSizeExpr,
                            errDropExpr,
                        )
                        readCodeBuilder.addStatement("%L(reinterpreted, retAutoArena, confinedArena = retOwnerArena, sharedFlag = flag)", errFromPointerExpr)
                    }
                    is XrossType.RustString -> {
                        readCodeBuilder.addStatement("val s = errPtr.reinterpret(%T.MAX_VALUE).getString(0)", Long::class)
                        readCodeBuilder.addStatement("Companion.xrossFreeStringHandle.invokeExact(errPtr)")
                        readCodeBuilder.addStatement("s")
                    }
                    else -> {
                        readCodeBuilder.addStatement("val v = errPtr.get(%M, 0)", errTy.layoutMember)
                        readCodeBuilder.addStatement("Companion.dropHandle.invokeExact(errPtr)")
                        readCodeBuilder.addStatement("v")
                    }
                }
                readCodeBuilder.endControlFlow()
                readCodeBuilder.addStatement("res = Result.failure<%T>(%T(errVal)) as %T", okKType, ClassName("$basePackage.xross.runtime", "XrossException"), kType)
                readCodeBuilder.endControlFlow()
            }

            is XrossType.Bool -> readCodeBuilder.addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement(
                    "val rawSegment = Companion.${baseName}StrGetHandle.invokeExact(this.segment) as %T",
                    MemorySegment::class,
                )
                readCodeBuilder.addStatement(
                    "res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class,
                )
                readCodeBuilder.addStatement(
                    "if (rawSegment != %T.NULL) Companion.xrossFreeStringHandle.invokeExact(rawSegment)",
                    MemorySegment::class,
                )
            }

            else -> readCodeBuilder.addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
        }

        return FunSpec.getterBuilder().addCode(
            """
            var stamp = this.sl.tryOptimisticRead()
            var res: %T
            // Optimistic read
            %L
            if (!this.sl.validate(stamp)) {
                stamp = this.sl.readLock()
                try {
                    // Pessimistic read
                    %L
                } finally { this.sl.unlockRead(stamp) }
            }
            return res
            """.trimIndent(),
            kType,
            readCodeBuilder.build(),
            readCodeBuilder.build(),
        ).build()
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
                writeCodeBuilder.addStatement("Companion.${baseName}StrSetHandle.invokeExact(this.segment, allocated) as Unit")
                writeCodeBuilder.endControlFlow()
            }

            is XrossType.Optional -> {
                writeCodeBuilder.beginControlFlow("if (v == null)")
                writeCodeBuilder.addStatement("Companion.${baseName}OptSetHandle.invokeExact(this.segment, %T.NULL) as Unit", MemorySegment::class)
                writeCodeBuilder.nextControlFlow("else")
                val inner = field.ty.inner
                when (inner) {
                    is XrossType.RustString -> {
                        writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                        writeCodeBuilder.addStatement("val allocated = arena.allocateFrom(v)")
                        writeCodeBuilder.addStatement("Companion.${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                        writeCodeBuilder.endControlFlow()
                    }

                    is XrossType.Object -> {
                        writeCodeBuilder.addStatement("Companion.${baseName}OptSetHandle.invokeExact(this.segment, v.segment) as Unit")
                    }

                    else -> {
                        writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                        writeCodeBuilder.addStatement("val allocated = arena.allocate(%M, v)", inner.layoutMember)
                        writeCodeBuilder.addStatement("Companion.${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                        writeCodeBuilder.endControlFlow()
                    }
                }
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
