package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.FFMConstants.ADDRESS
import org.xross.generator.util.FFMConstants.FUNCTION_DESCRIPTOR
import org.xross.generator.util.FFMConstants.JAVA_INT
import org.xross.generator.util.FFMConstants.VAL_LAYOUT
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*

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
                val methodMeta = meta.methods.find { it.name == suffix }
                val handleMode = methodMeta?.handleMode ?: HandleMode.Normal
                val isPanicable = handleMode is HandleMode.Panicable
                val desc = when (suffix) {
                    "drop" -> if (isPanicable) {
                        CodeBlock.of("%T.of(%L, %M)", FUNCTION_DESCRIPTOR, FFMConstants.XROSS_RESULT_LAYOUT_CODE, ADDRESS)
                    } else {
                        CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    }
                    "layout" -> CodeBlock.of("%T.of(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    else -> CodeBlock.of("%T.of(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
                }
                val options = when (handleMode) {
                    is HandleMode.Critical -> CodeBlock.of(", %T.critical(%L)", java.lang.foreign.Linker.Option::class.asTypeName(), handleMode.allowHeapAccess)
                    else -> CodeBlock.of("")
                }
                init.addStatement("this.${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L%L)", symbol, desc, options)
            }
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
        val argLayouts = constructor?.args?.map { it.ty.layoutCode } ?: emptyList()
        val retLayout = if (constructor?.handleMode is HandleMode.Panicable) {
            FFMConstants.XROSS_RESULT_LAYOUT_CODE
        } else {
            CodeBlock.of("%M", ADDRESS)
        }
        val desc = if (argLayouts.isEmpty()) {
            CodeBlock.of("%T.of(%L)", FUNCTION_DESCRIPTOR, retLayout)
        } else {
            CodeBlock.of("%T.of(%L, %L)", FUNCTION_DESCRIPTOR, retLayout, argLayouts.joinToCode(", "))
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
            val argLayouts = v.fields.map { it.ty.layoutCode }
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

    private fun resolvePropertyHandles(init: CodeBlock.Builder, prefix: String, fields: List<XrossField>, isOpaque: Boolean = false) {
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
                            "this.${baseCamel}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M))",
                            getSymbol,
                            FUNCTION_DESCRIPTOR,
                            field.ty.layoutCode,
                            ADDRESS,
                        )
                        init.addStatement(
                            "this.${baseCamel}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L))",
                            setSymbol,
                            FUNCTION_DESCRIPTOR,
                            ADDRESS,
                            field.ty.layoutCode,
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
        val getSymbol = "${prefix}_property_${rawName}_${suffix}_get"
        val setSymbol = "${prefix}_property_${rawName}_${suffix}_set"
        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
            getSymbol,
            FUNCTION_DESCRIPTOR,
            MemberName(VAL_LAYOUT, retLabel),
            ADDRESS,
        )
        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %M))",
            setSymbol,
            FUNCTION_DESCRIPTOR,
            ADDRESS,
            MemberName(VAL_LAYOUT, argLayout),
        )
    }

    private fun resolveMethodHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        meta.methods.filter { !it.isConstructor && it.name != "drop" && it.name != "layout" }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            method.args.forEach {
                args.add(it.ty.layoutCode)
            }

            val isPanicable = method.handleMode is HandleMode.Panicable
            val desc = if (method.ret is XrossType.Void && !method.isAsync && !isPanicable) {
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, args.joinToCode(", "))
            } else {
                val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))
                val retLayout = when {
                    method.isAsync -> FFMConstants.XROSS_TASK_LAYOUT_CODE
                    isPanicable -> FFMConstants.XROSS_RESULT_LAYOUT_CODE
                    else -> method.ret.layoutCode
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
