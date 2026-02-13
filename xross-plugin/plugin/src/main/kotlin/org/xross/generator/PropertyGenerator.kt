package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.ref.WeakReference

object PropertyGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")

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
                    .getter(buildGetter(field, vhName, kType, backingFieldName, selfType, basePackage))

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, kType, backingFieldName, selfType))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"
        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        when (val ty = field.ty) {
            is XrossType.Object -> {
                // キャッシュチェック
                body.addStatement("val cached = this.$backingFieldName?.get()")
                body.beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                body.addStatement("res = cached")
                body.nextControlFlow("else")

                val isOwned = ty.ownership == XrossType.Ownership.Owned
                val sizeExpr = if (kType == selfType) CodeBlock.of("Companion.STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                val fromPointerExpr = if (kType == selfType) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                val ffiHelpers = ClassName("$basePackage.xross.runtime", "FfiHelpers")

                body.addStatement("val resSeg = %T.resolveFieldSegment(this.segment, ${if (isOwned) "null" else "Companion.$vhName"}, Companion.$offsetName, $sizeExpr, $isOwned)", ffiHelpers)
                body.addStatement("res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                body.addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                body.endControlFlow()
            }

            is XrossType.Optional -> {
                body.addStatement("val resRaw = Companion.${baseName}OptGetHandle.invokeExact(this.segment) as %T", MemorySegment::class)
                body.beginControlFlow("if (resRaw == %T.NULL)", MemorySegment::class).addStatement("res = null").nextControlFlow("else")
                body.add("res = ")
                body.addResultVariantResolution(ty.inner, "resRaw", GeneratorUtils.resolveReturnType(ty.inner, basePackage), selfType, basePackage, "Companion.dropHandle")
                body.endControlFlow()
            }

            is XrossType.Result -> {
                body.addStatement("val resRaw = Companion.${baseName}ResGetHandle.invokeExact(this.autoArena as %T, this.segment) as %T", SegmentAllocator::class, MemorySegment::class)
                body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", FFMConstants.JAVA_BYTE)
                body.addStatement("val ptr = resRaw.get(%M, 8L)", FFMConstants.ADDRESS)

                body.beginControlFlow("if (isOk)")
                body.add("val okVal = ")
                body.addResultVariantResolution(ty.ok, "ptr", GeneratorUtils.resolveReturnType(ty.ok, basePackage), selfType, basePackage, "Companion.dropHandle")
                body.addStatement("res = Result.success(okVal)")

                body.nextControlFlow("else")
                body.add("val errVal = ")
                body.addResultVariantResolution(ty.err, "ptr", GeneratorUtils.resolveReturnType(ty.err, basePackage), selfType, basePackage, "Companion.dropHandle")
                body.addStatement("res = Result.failure(%T(errVal))", ClassName("$basePackage.xross.runtime", "XrossException"))
                body.endControlFlow()
            }

            is XrossType.RustString -> {
                val callExpr = "Companion.${baseName}StrGetHandle.invokeExact(this.segment)"
                GeneratorUtils.addRustStringResolution(body, callExpr, "res", isAssignment = true)
            }

            is XrossType.Bool -> body.addStatement("res = (Companion.$vhName.get(this.segment, Companion.$offsetName) as Byte) != (0).toByte()")
            else -> body.addStatement("res = Companion.$vhName.get(this.segment, Companion.$offsetName) as %T", kType)
        }

        return GeneratorUtils.buildOptimisticReadGetter(kType, body.build())
    }
    private fun buildSetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Access")

        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (val ty = field.ty) {
            is XrossType.Object -> {
                body.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Invalid Arg")
                if (ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (kType == selfType) CodeBlock.of("Companion.STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                    body.addStatement("this.segment.asSlice(Companion.$offsetName, %L).copyFrom(v.segment)", sizeExpr)
                } else {
                    body.addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, v.segment)")
                }
                if (backingFieldName != null) body.addStatement("this.$backingFieldName = null")
            }

            // String, Optional, Result はすべて Temporary Arena を必要とするグループ
            is XrossType.RustString, is XrossType.Optional, is XrossType.Result -> {
                body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
                when (ty) {
                    is XrossType.RustString -> {
                        body.addStatement("Companion.${baseName}StrSetHandle.invokeExact(this.segment, arena.allocateFrom(v)) as Unit")
                    }
                    is XrossType.Optional -> {
                        body.addStatement("val allocated = if (v == null) %T.NULL else %L", MemorySegment::class, generateAllocMsg(ty.inner, "v"))
                        body.addStatement("Companion.${baseName}OptSetHandle.invokeExact(this.segment, allocated) as Unit")
                    }
                    is XrossType.Result -> {
                        // Resultの構造体構築を共通化
                        body.addStatement("val xrossRes = arena.allocate(%L)", FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                        body.beginControlFlow("if (v.isSuccess)")
                        body.addStatement("xrossRes.set(%M, 0L, 1.toByte())", FFMConstants.JAVA_BYTE)
                        body.addStatement("val okAllocated = %L", generateAllocMsg(ty.ok, "v.getOrNull()!!"))
                        body.addStatement("xrossRes.set(%M, 8L, okAllocated)", FFMConstants.ADDRESS)
                        body.nextControlFlow("else")
                        body.addStatement("xrossRes.set(%M, 0L, 0.toByte())", FFMConstants.JAVA_BYTE)
                        body.addStatement("xrossRes.set(%M, 8L, %T.NULL)", FFMConstants.ADDRESS, MemorySegment::class)
                        body.endControlFlow()
                        body.addStatement("Companion.${baseName}ResSetHandle.invokeExact(this.segment, xrossRes) as Unit")
                    }
                }
                body.endControlFlow()
            }

            is XrossType.Bool -> body.addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, if (v) 1.toByte() else 0.toByte())")
            else -> body.addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, v)")
        }

        // 共通のLockラッパーでラップして返す
        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
            "val stamp = this.sl.writeLock()\ntry { %L } finally { this.sl.unlockWrite(stamp) }",
            body.build(),
        ).build()
    }

    // ヘルパー: 型に応じた allocate 式を返す
    private fun generateAllocMsg(ty: XrossType, valueName: String): CodeBlock = when (ty) {
        is XrossType.Object -> CodeBlock.of("$valueName.segment")
        is XrossType.RustString -> CodeBlock.of("arena.allocateFrom($valueName)")
        is XrossType.F32 -> CodeBlock.of("MemorySegment.ofAddress(%L.toRawBits().toLong())", valueName)
        is XrossType.F64 -> CodeBlock.of("MemorySegment.ofAddress(%L.toRawBits())", valueName)
        is XrossType.Bool -> CodeBlock.of("MemorySegment.ofAddress(if (%L) 1L else 0L)", valueName)
        else -> {
            // Integer types <= 8 bytes
            CodeBlock.of("MemorySegment.ofAddress(%L.toLong())", valueName)
        }
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
                            .addStatement("return Companion.$vhName.getVolatile(this@${className(classBuilder)}.segment, Companion.$offsetName) as %T", kType)
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
                    .beginControlFlow("if (Companion.$vhName.compareAndSet(this@${className(classBuilder)}.segment, Companion.$offsetName, current, next))")
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
