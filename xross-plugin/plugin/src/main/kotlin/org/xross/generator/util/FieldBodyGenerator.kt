package org.xross.generator.util

import com.squareup.kotlinpoet.*
import org.xross.structures.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.ref.WeakReference

object FieldBodyGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    fun buildGetterBody(
        field: XrossField,
        vhName: String,
        offsetName: String,
        kType: TypeName,
        selfType: ClassName,
        backingFieldName: String?,
        basePackage: String,
        handleNameProvider: (XrossType) -> String = { "" },
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, "Access error")

        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val xrossRuntime = ClassName("$basePackage.xross.runtime", "XrossRuntime")

        body.apply {
            when (val ty = field.ty) {
                is XrossType.Object -> {
                    if (backingFieldName != null) {
                        addStatement("val cached = this.$backingFieldName?.get()")
                        beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                        addStatement("res = cached")
                        nextControlFlow("else")
                    }

                    val isOwned = ty.ownership == XrossType.Ownership.Owned
                    val isBoxed = ty.ownership == XrossType.Ownership.Boxed
                    val (sizeExpr, _, fromPointerExpr) = GeneratorUtils.compareExprs(kType, selfType)

                    if (isBoxed) {
                        addStatement("val ptr = this.segment.get(%M, $offsetName)", FFMConstants.ADDRESS)
                        beginControlFlow("if (ptr == %T.NULL)", MEMORY_SEGMENT)
                        addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Boxed field is NULL")
                        endControlFlow()
                        addStatement("val resSeg = ptr.reinterpret(%L)", sizeExpr)
                    } else {
                        addStatement(
                            "val resSeg = %T.resolveFieldSegment(this.segment, ${if (isOwned || vhName == "null") "null" else vhName}, $offsetName, %L, %L)",
                            xrossRuntime,
                            sizeExpr,
                            isOwned,
                        )
                    }

                    addStatement("res = %L(resSeg, this.arena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)

                    if (backingFieldName != null) {
                        addStatement("this.$backingFieldName = %T(res)", WeakReference::class)
                        endControlFlow()
                    }
                }

                is XrossType.Optional -> {
                    val handleName = handleNameProvider(ty)
                    if (handleName.isNotEmpty()) {
                        addStatement("val resRaw = $handleName.invokeExact(this.segment) as %T", MEMORY_SEGMENT)
                        add("res = ")
                        beginControlFlow("if (resRaw == %T.NULL)", MEMORY_SEGMENT).addStatement("null")
                            .nextControlFlow("else")
                        addResultVariantResolution(
                            ty.inner,
                            "resRaw",
                            GeneratorUtils.resolveReturnType(ty.inner, basePackage),
                            selfType,
                            basePackage,
                            "dropHandle",
                        )
                        endControlFlow()
                    } else {
                        addStatement("throw %T(%S)", RuntimeException::class, "Optional handle not found")
                    }
                }

                is XrossType.Result -> {
                    val handleName = handleNameProvider(ty)
                    if (handleName.isNotEmpty()) {
                        addStatement(
                            "val resRaw = $handleName.invokeExact(this.arena as %T, this.segment) as %T",
                            SegmentAllocator::class.asTypeName(),
                            MEMORY_SEGMENT,
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
                            "dropHandle",
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
                            "dropHandle",
                        )
                        addStatement(
                            "Result.failure(%T(errVal))",
                            ClassName("$basePackage.xross.runtime", "XrossException"),
                        )
                        endControlFlow()
                    } else {
                        addStatement("throw %T(%S)", RuntimeException::class, "Result handle not found")
                    }
                }

                is XrossType.RustString -> {
                    val handleName = handleNameProvider(ty)
                    if (handleName.isNotEmpty() && !isEnumVariant(vhName)) {
                        addRustStringResolution("$handleName.invokeExact(this.arena as ${SegmentAllocator::class.java.canonicalName}, this.segment)", "res", isAssignment = true, basePackage = basePackage)
                    } else {
                        val callExpr = "$vhName.get(this.segment, $offsetName)"
                        addRustStringResolution(callExpr, "res", isAssignment = true, shouldFree = false, basePackage = basePackage)
                    }
                }

                is XrossType.Bool -> addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
                else -> {
                    if (vhName == "null") {
                        addStatement("throw %T(%S)", RuntimeException::class, "Cannot access non-primitive field via VarHandle")
                    } else {
                        addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
                    }
                }
            }
        }
        return body.build()
    }

    private fun isEnumVariant(vhName: String): Boolean = vhName.startsWith("VH_") && vhName.count { it == '_' } >= 2

    fun buildSetterBody(
        field: XrossField,
        vhName: String,
        offsetName: String,
        kType: TypeName,
        selfType: ClassName,
        backingFieldName: String?,
        basePackage: String,
        handleNameProvider: (XrossType) -> String = { "" },
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, "Invalid Access")

        when (val ty = field.ty) {
            is XrossType.Object -> {
                body.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, "Invalid Arg")
                if (ty.ownership == XrossType.Ownership.Owned) {
                    val (sizeExpr, _, _) = GeneratorUtils.compareExprs(kType, selfType)
                    body.addStatement("this.segment.asSlice($offsetName, %L).copyFrom(v.segment)", sizeExpr)
                } else {
                    if (vhName == "null") {
                        body.addStatement("this.segment.set(%M, $offsetName, v.segment)", FFMConstants.ADDRESS)
                    } else {
                        body.addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                    }
                }
                if (backingFieldName != null) body.addStatement("this.$backingFieldName = null")
            }

            is XrossType.RustString, is XrossType.Optional, is XrossType.Result -> {
                val handleName = handleNameProvider(ty)
                if (handleName.isNotEmpty()) {
                    body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
                    val callArgs = mutableListOf<CodeBlock>()
                    body.addArgumentPreparation(ty, "v", callArgs, basePackage = basePackage)
                    body.addStatement("$handleName.invoke(this.segment, ${callArgs.joinToString(", ")})")
                    body.endControlFlow()
                } else {
                    if (ty is XrossType.RustString) {
                        body.addStatement("// TODO: Setter for RustString in Enum variant via VarHandle if possible")
                    }
                }
            }

            is XrossType.Bool -> body.addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
            else -> {
                if (vhName == "null") {
                    body.addStatement("throw %T(%S)", RuntimeException::class, "Cannot access non-primitive field via VarHandle")
                } else {
                    body.addStatement("$vhName.set(this.segment, $offsetName, v)")
                }
            }
        }

        if (backingFieldName != null && field.ty !is XrossType.Object) {
            body.addStatement("this.$backingFieldName = null")
        }

        return body.build()
    }
}
