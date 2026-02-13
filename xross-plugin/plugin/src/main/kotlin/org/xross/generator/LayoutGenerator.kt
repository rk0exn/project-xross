package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.asTypeName
import org.xross.generator.FFMConstants.MEMORY_LAYOUT
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType

object LayoutGenerator {
    fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        init.addStatement("val layouts = mutableListOf<%T>()", MEMORY_LAYOUT)
        init.addStatement("var currentOffsetPos = 0L")
        init.addStatement("val matchedFields = mutableSetOf<String>()")

        init.beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val f = parts[i].split(':')")
            .addStatement("if (f.size < 3) continue")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            .beginControlFlow("if (fOffset > currentOffsetPos)")
            .addStatement("layouts.add(%T.paddingLayout(fOffset - currentOffsetPos))", MEMORY_LAYOUT)
            .addStatement("currentOffsetPos = fOffset")
            .endControlFlow()
            .beginControlFlow("if (fName in matchedFields)")
            .addStatement("continue")
            .endControlFlow()

        meta.fields.forEachIndexed { idx, field ->
            val branch = if (idx == 0) "if" else "else if"
            init.beginControlFlow("$branch (fName == %S)", field.name)
            val kotlinSize = field.ty.kotlinSize
            val alignmentCode = if (field.safety == XrossThreadSafety.Atomic) "" else ".withByteAlignment(1)"

            if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                init.addStatement("layouts.add(%T.paddingLayout(fSize).withName(%S))", MEMORY_LAYOUT, field.name)
            } else {
                init.addStatement("layouts.add(%M.withName(%S)%L)", field.ty.layoutMember, field.name, alignmentCode)
                init.beginControlFlow("if (fSize > $kotlinSize)")
                    .addStatement("layouts.add(%T.paddingLayout(fSize - $kotlinSize))", MEMORY_LAYOUT)
                    .endControlFlow()
            }

            init.addStatement("this.OFFSET_${field.name.toCamelCase()} = fOffset")
            if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                init.addStatement("this.VH_${field.name.toCamelCase()} = %M.varHandle()", field.ty.layoutMember)
            }
            init.addStatement("currentOffsetPos = fOffset + fSize")
            init.addStatement("matchedFields.add(fName)")
            init.endControlFlow()
        }

        init.beginControlFlow("else")
            .addStatement("layouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)
            .addStatement("currentOffsetPos = fOffset + fSize")
            .endControlFlow()
        init.endControlFlow()

        init.beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")
            .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MEMORY_LAYOUT)
            .endControlFlow()

        init.addStatement(
            "this.LAYOUT = if (layouts.isEmpty()) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout(*layouts.toTypedArray())",
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
        )
    }

    fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement("val variantRegex = %T(%S)", Regex::class.asTypeName(), "(\\w+)(?:\\{(.*)})?")
            .beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val match = variantRegex.find(parts[i]) ?: continue")
            .addStatement("val vName = match.groupValues[1]")
            .addStatement("val vFields = match.groupValues[2]")

        val anyVariantHasFields = meta.variants.any { it.fields.isNotEmpty() }
        if (anyVariantHasFields) {
            init.beginControlFlow("if (vFields.isNotEmpty())")
                .beginControlFlow("for (fInfo in vFields.split(';'))")
                .addStatement("if (fInfo.isBlank()) continue")
                .addStatement("val f = fInfo.split(':')")
                .addStatement("val fName = f[0]; val fOffsetL = f[1].toLong(); val fSizeL = f[2].toLong()")

            meta.variants.filter { it.fields.isNotEmpty() }.forEach { variant ->
                init.beginControlFlow("if (vName == %S)", variant.name)
                variant.fields.forEach { field ->
                    init.beginControlFlow("if (fName == %S)", field.name)
                        .addStatement("val vLayouts = mutableListOf<%T>()", MEMORY_LAYOUT)
                        .addStatement("if (fOffsetL > 0) vLayouts.add(%T.paddingLayout(fOffsetL))", MEMORY_LAYOUT)

                    val kotlinSize = field.ty.kotlinSize
                    if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                        init.addStatement("vLayouts.add(%T.paddingLayout(fSizeL).withName(fName))", MEMORY_LAYOUT)
                    } else {
                        val alignmentCode = if (field.safety == XrossThreadSafety.Atomic) "" else ".withByteAlignment(1)"
                        init.addStatement("vLayouts.add(%M.withName(fName)%L)", field.ty.layoutMember, alignmentCode)
                        init.beginControlFlow("if (fSizeL > $kotlinSize)")
                            .addStatement("vLayouts.add(%T.paddingLayout(fSizeL - $kotlinSize))", MEMORY_LAYOUT)
                            .endControlFlow()
                    }

                    init.addStatement("val remaining = STRUCT_SIZE - fOffsetL - fSizeL")
                        .addStatement("if (remaining > 0) vLayouts.add(%T.paddingLayout(remaining))", MEMORY_LAYOUT)
                        .addStatement("val vLayout = %T.structLayout(*vLayouts.toTypedArray())", MEMORY_LAYOUT)
                        .addStatement("this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL")

                    if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                        init.addStatement("this.VH_${variant.name}_${field.name.toCamelCase()} = %M.varHandle()", field.ty.layoutMember)
                    }
                    init.endControlFlow()
                }
                init.endControlFlow()
            }
            init.endControlFlow().endControlFlow()
        }
        init.endControlFlow()
        init.addStatement(
            "this.LAYOUT = if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout()",
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
            MEMORY_LAYOUT,
        )
    }
}
