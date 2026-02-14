package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import org.xross.generator.FFMConstants.ADDRESS
import org.xross.generator.FFMConstants.FUNCTION_DESCRIPTOR
import org.xross.generator.FFMConstants.JAVA_INT
import org.xross.generator.FFMConstants.VAL_LAYOUT
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.HandleMode
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethodType
import org.xross.structures.XrossType

object HandleResolver {
    fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        // Basic handles
        init.addStatement(
            "this.xrossFreeStringHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M))",
            "xross_free_string",
            FUNCTION_DESCRIPTOR,
            ADDRESS,
        )

        if (meta !is XrossDefinition.Function) {
            listOf("drop", "layout").forEach { suffix ->
                val symbol = "${meta.symbolPrefix}_$suffix"
                val desc = when (suffix) {
                    "drop" -> CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    "layout" -> CodeBlock.of("%T.of(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    else -> CodeBlock.of("%T.of(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
                }
                init.addStatement("this.${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
            }
        }

        if (meta.methods.any { it.name == "clone" }) {
            val symbol = "${meta.symbolPrefix}_clone"
            val desc = CodeBlock.of("%T.of(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
            init.addStatement("this.cloneHandle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
        }

        when (meta) {
            is XrossDefinition.Struct -> resolveStructHandles(init, meta)
            is XrossDefinition.Enum -> resolveEnumHandles(init, meta)
            is XrossDefinition.Opaque -> resolveOpaqueHandles(init, meta)
            is XrossDefinition.Function -> {}
        }

        resolveMethodHandles(init, meta)
    }

    private fun resolveStructHandles(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        val constructor = meta.methods.find { it.isConstructor && it.name == "new" }
        val argLayouts = constructor?.args?.map { if (it.ty is XrossType.Result) FFMConstants.XROSS_RESULT_LAYOUT_CODE else CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
        val desc = if (argLayouts.isEmpty()) {
            CodeBlock.of("%T.of(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
        } else {
            CodeBlock.of("%T.of(%M, %L)", FUNCTION_DESCRIPTOR, ADDRESS, argLayouts.joinToCode(", "))
        }

        init.addStatement("this.newHandle = linker.downcallHandle(lookup.find(%S).get(), %L)", "${meta.symbolPrefix}_new", desc)
        resolvePropertyHandles(init, meta.symbolPrefix, meta.fields)
    }

    private fun resolveEnumHandles(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement(
            "this.getTagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
            "${meta.symbolPrefix}_get_tag",
            FUNCTION_DESCRIPTOR,
            JAVA_INT,
            ADDRESS,
        )
        init.addStatement(
            "this.getVariantNameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
            "${meta.symbolPrefix}_get_variant_name",
            FUNCTION_DESCRIPTOR,
            ADDRESS,
            ADDRESS,
        )

        meta.variants.forEach { v ->
            val argLayouts = v.fields.map { if (it.ty is XrossType.Result) FFMConstants.XROSS_RESULT_LAYOUT_CODE else CodeBlock.of("%M", it.ty.layoutMember) }
            val desc = if (argLayouts.isEmpty()) {
                CodeBlock.of("%T.of(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
            } else {
                CodeBlock.of("%T.of(%M, %L)", FUNCTION_DESCRIPTOR, ADDRESS, argLayouts.joinToCode(", "))
            }
            init.addStatement("this.new${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", "${meta.symbolPrefix}_new_${v.name}", desc)
        }
    }

    private fun resolveOpaqueHandles(init: CodeBlock.Builder, meta: XrossDefinition.Opaque) {
        resolvePropertyHandles(init, meta.symbolPrefix, meta.fields, isOpaque = true)
    }

    private fun resolvePropertyHandles(init: CodeBlock.Builder, prefix: String, fields: List<org.xross.structures.XrossField>, isOpaque: Boolean = false) {
        fields.forEach { field ->
            val baseCamel = field.name.toCamelCase()
            when (field.ty) {
                is XrossType.RustString -> {
                    addGetterSetter(init, prefix, field.name, baseCamel, "str", "ADDRESS")
                }
                is XrossType.Optional -> {
                    addGetterSetter(init, prefix, field.name, baseCamel, "opt", "ADDRESS")
                }
                is XrossType.Result -> {
                    val getSymbol = "${prefix}_property_${field.name}_res_get"
                    val setSymbol = "${prefix}_property_${field.name}_res_set"
                    init.addStatement(
                        "this.${baseCamel}ResGetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M))",
                        getSymbol,
                        FUNCTION_DESCRIPTOR,
                        FFMConstants.XROSS_RESULT_LAYOUT_CODE,
                        ADDRESS,
                    )
                    init.addStatement(
                        "this.${baseCamel}ResSetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L))",
                        setSymbol,
                        FUNCTION_DESCRIPTOR,
                        ADDRESS,
                        FFMConstants.XROSS_RESULT_LAYOUT_CODE,
                    )
                }
                else -> {
                    if (isOpaque) {
                        val getSymbol = "${prefix}_property_${field.name}_get"
                        val setSymbol = "${prefix}_property_${field.name}_set"
                        init.addStatement(
                            "this.${baseCamel}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
                            getSymbol,
                            FUNCTION_DESCRIPTOR,
                            field.ty.layoutMember,
                            ADDRESS,
                        )
                        init.addStatement(
                            "this.${baseCamel}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %M))",
                            setSymbol,
                            FUNCTION_DESCRIPTOR,
                            ADDRESS,
                            field.ty.layoutMember,
                        )
                    }
                }
            }
        }
    }

    private fun addGetterSetter(
        init: CodeBlock.Builder,
        prefix: String,
        rawName: String,
        camelName: String,
        suffix: String,
        retLayout: String,
    ) {
        val retLabel = "ADDRESS"
        val argLayout = "ADDRESS"
        val isVoidSetter = true
        val getSymbol = "${prefix}_property_${rawName}_${suffix}_get"
        val setSymbol = "${prefix}_property_${rawName}_${suffix}_set"
        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
            getSymbol,
            FUNCTION_DESCRIPTOR,
            MemberName(VAL_LAYOUT, retLayout),
            ADDRESS,
        )
        val setterDesc = if (isVoidSetter) {
            CodeBlock.of("ofVoid(%M, %M)", ADDRESS, MemberName(VAL_LAYOUT, argLayout))
        } else {
            CodeBlock.of("of(%M, %M, %M)", MemberName(VAL_LAYOUT, retLayout), ADDRESS, MemberName(VAL_LAYOUT, argLayout))
        }
        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.%L)",
            setSymbol,
            FUNCTION_DESCRIPTOR,
            setterDesc,
        )
    }

    private fun resolveMethodHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        meta.methods.filter { !it.isConstructor }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            method.args.forEach {
                if (it.ty is XrossType.Result) {
                    args.add(FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                } else {
                    args.add(CodeBlock.of("%M", it.ty.layoutMember))
                }
            }

            val desc = if (method.ret is XrossType.Void) {
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, args.joinToCode(", "))
            } else {
                val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))
                val retLayout = when {
                    method.handleMode is HandleMode.Panicable -> FFMConstants.XROSS_RESULT_LAYOUT_CODE
                    method.ret is XrossType.Result -> FFMConstants.XROSS_RESULT_LAYOUT_CODE
                    else -> CodeBlock.of("%M", method.ret.layoutMember)
                }
                CodeBlock.of("%T.of(%L%L)", FUNCTION_DESCRIPTOR, retLayout, argsPart)
            }

            val options = when (val mode = method.handleMode) {
                is HandleMode.Critical -> {
                    CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), mode.allowHeapAccess)
                }
                else -> CodeBlock.of("")
            }

            init.addStatement(
                "this.${method.name.toCamelCase()}Handle = linker.downcallHandle(lookup.find(%S).get(), %L%L)",
                method.symbol,
                desc,
                options,
            )
        }
    }
}
