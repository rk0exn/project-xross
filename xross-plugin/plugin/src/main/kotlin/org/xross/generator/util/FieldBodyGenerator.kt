package org.xross.generator.util

import com.squareup.kotlinpoet.*
import org.xross.structures.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout

object FieldBodyGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    private fun CodeBlock.Builder.addAliveCheck(message: String) {
        addStatement("if (this.segment == %T.NULL || !this.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, message)
    }

    fun buildGetterBody(
        field: XrossField,
        handleBaseName: String,
        vhName: String,
        offsetName: String,
        kType: TypeName,
        selfType: ClassName,
        backingFieldName: String?,
        basePackage: String,
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addAliveCheck("Access error")

        body.apply {
            if (backingFieldName != null) {
                addStatement("val cached = this.$backingFieldName")
                if (field.ty is XrossType.Object) {
                    beginControlFlow("if (cached != null && cached.isValid)")
                } else {
                    beginControlFlow("if (cached != null)")
                }
                addStatement("res = cached")
                nextControlFlow("else")
            }

            when (val ty = field.ty) {
                is XrossType.Object -> {
                    val isOwned = ty.ownership == XrossType.Ownership.Owned
                    val isBoxed = ty.ownership == XrossType.Ownership.Boxed
                    val (sizeExpr, _, fromPointerExpr) = GeneratorUtils.compareExprs(kType, selfType)

                    if (isBoxed) {
                        addStatement("val ptr = this.segment.get(%T.ADDRESS, $offsetName)", ValueLayout::class)
                        beginControlFlow("if (ptr == %T.NULL)", MEMORY_SEGMENT)
                        addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Boxed field is NULL")
                        endControlFlow()
                        addStatement("val resSeg = ptr.reinterpret(%L)", sizeExpr)
                    } else {
                        val xrossRuntime = ClassName("$basePackage.xross.runtime", "XrossRuntime")
                        addStatement(
                            "val resSeg = %T.resolveFieldSegment(this.segment, ${if (isOwned || vhName == "null") "null" else vhName}, $offsetName, %L, %L)",
                            xrossRuntime,
                            sizeExpr,
                            isOwned,
                        )
                    }

                    addStatement("res = %L(resSeg, parent = this, isPersistent = false)", fromPointerExpr)
                }

                is XrossType.Optional -> {
                    val handleName = GeneratorUtils.getPropertyHandleName(handleBaseName, ty, true)
                    addStatement("val resRaw = $handleName.invokeExact(this.segment) as %T", MEMORY_SEGMENT)
                    add("res = ")
                    addOptionalResolution(ty.inner, "resRaw", selfType, basePackage)
                }

                is XrossType.Result -> {
                    val handleName = GeneratorUtils.getPropertyHandleName(handleBaseName, ty, true)
                    addStatement(
                        "val resRaw = $handleName.invokeExact(java.lang.foreign.Arena.ofAuto() as %T, this.segment) as %T",
                        SegmentAllocator::class.asTypeName(),
                        MEMORY_SEGMENT,
                    )
                    add("res = ")
                    addResultResolution(ty, "resRaw", selfType, basePackage)
                }

                is XrossType.RustString -> {
                    val handleName = GeneratorUtils.getPropertyHandleName(handleBaseName, ty, true)
                    if (handleName.isNotEmpty()) {
                        // Inline String conversion to avoid XrossString object overhead
                        addStatement("val outRaw = java.lang.foreign.Arena.ofAuto().run { $handleName.invokeExact(this as %T, this@${className(selfType)}.segment) as %T }", SegmentAllocator::class.asTypeName(), MEMORY_SEGMENT)
                        addStatement("val ptr = outRaw.get(%T.ADDRESS, 0L)", ValueLayout::class)
                        addStatement("val len = outRaw.get(%T.JAVA_LONG, 8L)", ValueLayout::class)
                        beginControlFlow("res = if (ptr == %T.NULL || len == 0L)", MEMORY_SEGMENT)
                        addStatement("%S", "")
                        nextControlFlow("else")
                        addStatement("val bytes = ptr.reinterpret(len).toArray(%T.JAVA_BYTE)", ValueLayout::class)
                        addStatement("String(bytes, java.nio.charset.StandardCharsets.UTF_8)")
                        endControlFlow()
                        addStatement("if (outRaw != %T.NULL) xrossFreeStringHandle.invoke(outRaw)", MEMORY_SEGMENT)
                    } else {
                        addStatement("res = %S", "") // Fallback
                    }
                }

                is XrossType.Bool -> addStatement("res = this.segment.get(%T.JAVA_BYTE, $offsetName) != (0).toByte()", ValueLayout::class)

                else -> {
                    val layout = ty.layoutMember
                    val needsCast = when (ty) {
                        is XrossType.I8, is XrossType.U8, is XrossType.I16, is XrossType.U16 -> false
                        is XrossType.I32, is XrossType.U32, is XrossType.I64, is XrossType.U64 -> false
                        is XrossType.ISize, is XrossType.USize -> false
                        is XrossType.F32, is XrossType.F64 -> false
                        is XrossType.Pointer -> false
                        else -> true
                    }
                    if (needsCast) {
                        addStatement("res = this.segment.get(%T.%L, $offsetName) as %T", ValueLayout::class, layout.simpleName, kType)
                    } else {
                        addStatement("res = this.segment.get(%T.%L, $offsetName)", ValueLayout::class, layout.simpleName)
                    }
                }
            }

            if (backingFieldName != null) {
                addStatement("this.$backingFieldName = res")
                endControlFlow()
            }
        }
        return body.build()
    }

    private fun isEnumVariant(vhName: String): Boolean = vhName.startsWith("VH_") && vhName.count { it == '_' } >= 2
    private fun className(cls: ClassName): String = cls.simpleName

    fun buildSetterBody(
        field: XrossField,
        handleBaseName: String,
        vhName: String,
        offsetName: String,
        kType: TypeName,
        selfType: ClassName,
        backingFieldName: String?,
        basePackage: String,
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addAliveCheck("Invalid Access")

        when (val ty = field.ty) {
            is XrossType.Object -> {
                body.addStatement("if (v.segment == %T.NULL || !v.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class, "Invalid Arg")
                if (ty.ownership == XrossType.Ownership.Owned) {
                    val (sizeExpr, _, _) = GeneratorUtils.compareExprs(kType, selfType)
                    body.addStatement("this.segment.asSlice($offsetName, %L).copyFrom(v.segment)", sizeExpr)
                } else {
                    body.addStatement("this.segment.set(%T.ADDRESS, $offsetName, v.segment)", ValueLayout::class)
                }
            }

            is XrossType.RustString, is XrossType.Optional, is XrossType.Result -> {
                val handleName = GeneratorUtils.getPropertyHandleName(handleBaseName, ty, false)
                if (handleName.isNotEmpty()) {
                    val callArgs = mutableListOf<CodeBlock>()
                    body.addArgumentPreparation(ty, "v", callArgs, basePackage = basePackage, arenaName = "java.lang.foreign.Arena.ofAuto()")
                    body.addStatement("$handleName.invoke(this.segment, ${callArgs.joinToString(", ")})")
                }
            }

            is XrossType.Bool -> body.addStatement("this.segment.set(%T.JAVA_BYTE, $offsetName, if (v) 1.toByte() else 0.toByte())", ValueLayout::class)
            else -> {
                val layout = ty.layoutMember
                body.addStatement("this.segment.set(%T.%L, $offsetName, v)", ValueLayout::class, layout.simpleName)
            }
        }

        if (backingFieldName != null) {
            // キャッシュをクリアするのではなく、セットした値をそのままキャッシュに保存する（最適化）
            body.addStatement("this.$backingFieldName = v")
        }

        return body.build()
    }
}
