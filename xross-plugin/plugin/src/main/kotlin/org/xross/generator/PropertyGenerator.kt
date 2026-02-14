package org.xross.generator

import com.squareup.kotlinpoet.*
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
            val kType = GeneratorUtils.resolveReturnType(field.ty, basePackage)

            val backingFieldName = GeneratorUtils.addBackingPropertyIfNeeded(classBuilder, field, baseName, kType)

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, kType)
            } else {
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(GeneratorUtils.buildFullGetter(kType, buildGetterBody(field, vhName, kType, backingFieldName, selfType, basePackage)))

                if (isMutable) propBuilder.setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildSetterBody(field, vhName, kType, backingFieldName, selfType)))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetterBody(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"
        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        body.apply {
            when (val ty = field.ty) {
                is XrossType.Object -> {
                    // キャッシュチェック
                    addStatement("val cached = this.$backingFieldName?.get()")
                    beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                    addStatement("res = cached")
                    nextControlFlow("else")

                    val isOwned = ty.ownership == XrossType.Ownership.Owned
                    val sizeExpr =
                        if (kType == selfType) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                    val fromPointerExpr =
                        if (kType == selfType) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                    val ffiHelpers = ClassName("$basePackage.xross.runtime", "FfiHelpers")

                    addStatement(
                        "val resSeg = %T.resolveFieldSegment(this.segment, ${if (isOwned) "null" else vhName}, $offsetName, $sizeExpr, $isOwned)",
                        ffiHelpers
                    )
                    addStatement(
                        "res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))",
                        fromPointerExpr,
                        flagType
                    )
                    addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                    endControlFlow()
                }

                is XrossType.Optional -> {
                    addStatement(
                        "val resRaw = ${baseName}OptGetHandle.invokeExact(this.segment) as %T",
                        MemorySegment::class
                    )
                    add("res = ")
                    beginControlFlow("if (resRaw == %T.NULL)", MemorySegment::class).addStatement("null")
                        .nextControlFlow("else")
                    addResultVariantResolution(
                        ty.inner,
                        "resRaw",
                        GeneratorUtils.resolveReturnType(ty.inner, basePackage),
                        selfType,
                        basePackage,
                        "dropHandle"
                    )
                    endControlFlow()
                }

                is XrossType.Result -> {
                    addStatement(
                        "val resRaw = ${baseName}ResGetHandle.invokeExact(this.autoArena as %T, this.segment) as %T",
                        java.lang.foreign.SegmentAllocator::class,
                        MemorySegment::class
                    )
                    addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", FFMConstants.JAVA_BYTE)
                    addStatement("val ptr = resRaw.get(%M, 8L)", FFMConstants.ADDRESS)

                    add("res = ")
                    beginControlFlow("if (isOk)")
                    add("val okVal = ")
                    addResultVariantResolution(
                        ty.ok,
                        "ptr",
                        GeneratorUtils.resolveReturnType(ty.ok, basePackage),
                        selfType,
                        basePackage,
                        "dropHandle"
                    )
                    addStatement("Result.success(okVal)")

                    nextControlFlow("else")
                    add("val errVal = ")
                    addResultVariantResolution(
                        ty.err,
                        "ptr",
                        GeneratorUtils.resolveReturnType(ty.err, basePackage),
                        selfType,
                        basePackage,
                        "dropHandle"
                    )
                    addStatement(
                        "Result.failure(%T(errVal))",
                        ClassName("$basePackage.xross.runtime", "XrossException")
                    )
                    endControlFlow()
                }

                is XrossType.RustString -> {
                    val callExpr = "${baseName}StrGetHandle.invokeExact(this.segment)"
                    addRustStringResolution(callExpr, "res", isAssignment = true)
                }

                is XrossType.Bool -> addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
                else -> addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
            }
        }
        return body.build()
    }

    private fun buildSetterBody(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Access")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (val ty = field.ty) {
            is XrossType.Object -> {
                body.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Arg")
                if (ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (kType == selfType) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                    body.addStatement("this.segment.asSlice($offsetName, %L).copyFrom(v.segment)", sizeExpr)
                } else {
                    body.addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                }
                if (backingFieldName != null) body.addStatement("this.$backingFieldName = null")
            }

            is XrossType.RustString, is XrossType.Optional, is XrossType.Result -> {
                body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
                when (ty) {
                    is XrossType.RustString -> {
                        body.addStatement("${baseName}StrSetHandle.invokeExact(this.segment, arena.allocateFrom(v)) as Unit")
                    }
                    is XrossType.Optional -> {
                        body.addStatement("val allocated = if (v == null) %T.NULL else %L", MemorySegment::class, GeneratorUtils.generateAllocMsg(ty.inner, "v"))
                        body.addStatement("${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                    }
                    is XrossType.Result -> {
                        body.addResultAllocation(ty, "v", "xrossRes")
                        body.addStatement("${baseName}ResSetHandle.invokeExact(this.segment, xrossRes) as Unit")
                    }
                }
                body.endControlFlow()
            }

            is XrossType.Bool -> body.addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
            else -> body.addStatement("$vhName.set(this.segment, $offsetName, v)")
        }

        return body.build()
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
