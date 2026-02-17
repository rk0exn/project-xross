package org.xross.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.FFMConstants.ADDRESS
import org.xross.generator.util.FFMConstants.FUNCTION_DESCRIPTOR
import org.xross.generator.util.FFMConstants.JAVA_BYTE
import org.xross.generator.util.FFMConstants.JAVA_INT
import org.xross.generator.util.FFMConstants.JAVA_LONG
import org.xross.generator.util.FFMConstants.VAL_LAYOUT
import org.xross.generator.util.GeneratorUtils
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*

object HandleResolver {
    fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        // Basic handles
        init.addStatement(
            "this.xrossFreeStringHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%L))",
            "xross_free_string",
            FUNCTION_DESCRIPTOR,
            FFMConstants.XROSS_STRING_LAYOUT_CODE,
        )

        if (meta !is XrossDefinition.Function) {
            listOf("drop", "layout").forEach { suffix ->
                val symbol = "${meta.symbolPrefix}_$suffix"
                val methodMeta = meta.methods.find { it.name == suffix }
                val handleMode = methodMeta?.handleMode ?: HandleMode.Normal
                val isPanicable = handleMode is HandleMode.Panicable
                val desc = when (suffix) {
                    "drop" -> if (isPanicable) {
                        CodeBlock.of("%T.ofVoid(%M, %M)", FUNCTION_DESCRIPTOR, ADDRESS, ADDRESS)
                    } else {
                        CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
                    }
                    "layout" -> CodeBlock.of("%T.ofVoid(%M)", FUNCTION_DESCRIPTOR, ADDRESS)
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

    private fun getArgLayouts(fields: List<XrossField>): List<CodeBlock> {
        val layouts = mutableListOf<CodeBlock>()
        fields.forEach {
            if (it.ty is XrossType.RustString) {
                layouts.add(CodeBlock.of("%M", ADDRESS))
                layouts.add(CodeBlock.of("%M", JAVA_LONG))
                layouts.add(CodeBlock.of("%M", JAVA_BYTE))
            } else {
                layouts.add(it.ty.layoutCode)
            }
        }
        return layouts
    }

    private fun resolveStructHandles(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        meta.methods.filter { it.isConstructor }.forEach { method ->
            val argLayouts = getArgLayouts(method.args)
            val isPanicable = method.handleMode is HandleMode.Panicable

            val desc = if (isPanicable) {
                val allArgs = mutableListOf(CodeBlock.of("%M", ADDRESS))
                allArgs.addAll(argLayouts)
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, allArgs.joinToCode(", "))
            } else {
                val retLayout = CodeBlock.of("%M", ADDRESS)
                if (argLayouts.isEmpty()) {
                    CodeBlock.of("%T.of(%L)", FUNCTION_DESCRIPTOR, retLayout)
                } else {
                    CodeBlock.of("%T.of(%L, %L)", FUNCTION_DESCRIPTOR, retLayout, argLayouts.joinToCode(", "))
                }
            }

            val handleName = GeneratorUtils.getHandleName(method)
            init.addStatement("this.%L = linker.downcallHandle(lookup.find(%S).get(), %L)", handleName, method.symbol, desc)
        }
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
            "this.getVariantNameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %M))",
            "${meta.symbolPrefix}_get_variant_name",
            FUNCTION_DESCRIPTOR,
            ADDRESS,
            ADDRESS,
        )

        meta.variants.forEach { v ->
            val argLayouts = getArgLayouts(v.fields)
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
        val isStr = suffix == "str"
        val getSymbol = "${prefix}_property_${rawName}_${suffix}_get"
        val setSymbol = "${prefix}_property_${rawName}_${suffix}_set"

        val getRetLayout = if (isStr) FFMConstants.XROSS_STRING_LAYOUT_CODE else MemberName(VAL_LAYOUT, retLayout)
        val setArgLayout = if (isStr) FFMConstants.XROSS_STRING_VIEW_LAYOUT_CODE else MemberName(VAL_LAYOUT, "ADDRESS")

        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}GetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%L, %M))",
            getSymbol,
            FUNCTION_DESCRIPTOR,
            getRetLayout,
            ADDRESS,
        )
        init.addStatement(
            "this.${camelName}${suffix.replaceFirstChar { it.uppercase() }}SetHandle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(%M, %L))",
            setSymbol,
            FUNCTION_DESCRIPTOR,
            ADDRESS,
            if (isStr) CodeBlock.of("%M, %M, %M", ADDRESS, JAVA_LONG, JAVA_BYTE) else setArgLayout,
        )
    }

    private fun matches(ty: XrossType, vararg kinds: kotlin.reflect.KClass<*>): Boolean = kinds.any { it.isInstance(ty) }

    private fun resolveMethodHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        meta.methods.filter { !it.isConstructor && it.name != "drop" && it.name != "layout" }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            args.addAll(getArgLayouts(method.args))

            val isComplexRet = method.ret is XrossType.RustString || method.isAsync

            val isPanicable = method.handleMode is HandleMode.Panicable
            val desc = if (method.ret is XrossType.Void && !method.isAsync && !isPanicable) {
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, args.joinToCode(", "))
            } else if (isPanicable || isComplexRet) {
                val allArgs = mutableListOf(CodeBlock.of("%M", ADDRESS))
                allArgs.addAll(args)
                CodeBlock.of("%T.ofVoid(%L)", FUNCTION_DESCRIPTOR, allArgs.joinToCode(", "))
            } else {
                val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))
                val retLayout = when {
                    method.isAsync -> FFMConstants.XROSS_TASK_LAYOUT_CODE
                    method.ret is XrossType.RustString -> FFMConstants.XROSS_STRING_LAYOUT_CODE
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
