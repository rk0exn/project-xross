package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

object CompanionGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val LAYOUT_TYPE = StructLayout::class.asClassName()
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    private val MEMORY_LAYOUT = MemoryLayout::class.asTypeName()
    
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")
    private val JAVA_LONG = MemberName(VAL_LAYOUT, "JAVA_LONG")

    private val XROSS_RESULT_LAYOUT = CodeBlock.of(
        "%T.structLayout(%M.withName(%S), %M.withName(%S))",
        MEMORY_LAYOUT, ADDRESS, "okPtr", ADDRESS, "errPtr"
    )

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        defineProperties(companionBuilder, meta)

        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class.asTypeName())
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class.asTypeName())

        resolveAllHandles(init, meta)

        init.add("\n// --- Native Layout Resolution ---\n")
        init.addStatement("var layoutRaw: %T = %T.NULL", MEMORY_SEGMENT, MEMORY_SEGMENT)
        init.addStatement("var layoutStr = %S", "")

        init.beginControlFlow("try")
            .addStatement("layoutRaw = layoutHandle.invokeExact() as %T", MEMORY_SEGMENT)
            .beginControlFlow("if (layoutRaw != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("layoutStr = layoutRaw.reinterpret(%T.MAX_VALUE).getString(0)", Long::class.asTypeName())
            .endControlFlow()
            .nextControlFlow("catch (e: %T)", Throwable::class.asTypeName())
            .addStatement("throw %T(e)", RuntimeException::class.asTypeName())
            .endControlFlow()

        init.beginControlFlow("if (layoutStr.isNotEmpty())")
            .addStatement("val parts = layoutStr.split(';')")
            .addStatement("this.STRUCT_SIZE = parts[0].toLong()")

        when (meta) {
            is XrossDefinition.Struct -> buildStructLayoutInit(init, meta)
            is XrossDefinition.Enum -> buildEnumLayoutInit(init, meta)
            is XrossDefinition.Opaque -> { }
        }

        init.beginControlFlow("if (layoutRaw != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("xrossFreeStringHandle.invokeExact(layoutRaw)")
            .endControlFlow()
            .nextControlFlow("else")
            .addStatement("this.STRUCT_SIZE = 0L")
            .addStatement("this.LAYOUT = %T.structLayout()", MEMORY_LAYOUT)
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }

                private fun defineProperties(builder: TypeSpec.Builder, meta: XrossDefinition) {

                    val handles = mutableListOf("dropHandle", "cloneHandle", "layoutHandle", "xrossFreeStringHandle")

            

                    when (meta) {

                        is XrossDefinition.Struct -> {

                            handles.add("newHandle")

                            meta.fields.forEach {

                                val baseCamel = it.name.toCamelCase()

                                builder.addProperty(

                                    PropertySpec.builder(

                                        "VH_$baseCamel", VH_TYPE, KModifier.INTERNAL, KModifier.LATEINIT

                                    ).mutable().build()

                                )

                                builder.addProperty(

                                    PropertySpec.builder(

                                        "OFFSET_$baseCamel", Long::class.asTypeName(), KModifier.INTERNAL

                                    ).mutable().initializer("0L").build()

                                )

                            }

                        }

            

                        is XrossDefinition.Enum -> {

                            handles.add("getTagHandle")

                            handles.add("getVariantNameHandle")

                            meta.variants.forEach { v ->

                                handles.add("new${v.name}Handle")

                                v.fields.forEach { f ->

                                    val baseCamel = f.name.toCamelCase()

                                    if (!(f.ty is XrossType.Object && f.ty.ownership == XrossType.Ownership.Owned)) {

                                        builder.addProperty(

                                            PropertySpec.builder("VH_${v.name}_$baseCamel",

                                                VH_TYPE,

                                                KModifier.INTERNAL, KModifier.LATEINIT

                                            ).mutable().build()

                                        )

                                    }

                                    builder.addProperty(

                                        PropertySpec.builder(

                                            "OFFSET_${v.name}_$baseCamel",

                                            Long::class.asTypeName(), KModifier.INTERNAL

                                        ).mutable().initializer("0L").build()

                                    )

                                }

                            }

                        }

            

                        is XrossDefinition.Opaque -> { }

                    }

            

        

                meta.methods.filter { !it.isConstructor }.forEach { handles.add("${it.name.toCamelCase()}Handle") }

        

                handles.distinct().forEach { name ->

                    builder.addProperty(PropertySpec.builder(name, HANDLE_TYPE, KModifier.INTERNAL).mutable().build())

                }

        

                builder.addProperty(

                    PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.INTERNAL).mutable()

                        .initializer("%T.structLayout()", MEMORY_LAYOUT)

                        .build()

                )

                builder.addProperty(

                    PropertySpec.builder("STRUCT_SIZE", Long::class.asTypeName(), KModifier.INTERNAL).mutable().initializer("0L").build()

                )

            }

        

    

        private fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {

            init.addStatement(

                "this.xrossFreeStringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))",

                FunctionDescriptor::class.asTypeName(),

                ADDRESS

            )

    

            listOf("drop", "layout", "clone").forEach { suffix ->

                val symbol = "${meta.symbolPrefix}_$suffix"

                val desc = when (suffix) {

                    "drop" -> CodeBlock.of("%T.ofVoid(%M)", FunctionDescriptor::class.asTypeName(), ADDRESS)

                    "layout" -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class.asTypeName(), ADDRESS)

                    else -> CodeBlock.of("%T.of(%M, %M)", FunctionDescriptor::class.asTypeName(), ADDRESS, ADDRESS)

                }

                val symbolFound = "lookup.find(%S)"

                init.addStatement("this.${suffix}Handle = linker.downcallHandle($symbolFound.get(), %L)", symbol, desc)

            }

    

            if (meta is XrossDefinition.Struct) {

                val constructor = meta.methods.find { it.isConstructor && it.name == "new" }

                val argLayouts = constructor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()

                val desc = if (argLayouts.isEmpty()) {

                    CodeBlock.of("%T.of(%M)", FunctionDescriptor::class.asTypeName(), ADDRESS)

                } else {

                    CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class.asTypeName(), ADDRESS, argLayouts.joinToCode(", "))

                }

                init.addStatement(

                    "this.newHandle = linker.downcallHandle(lookup.find(%S).get(), %L)",

                    "${meta.symbolPrefix}_new",

                    desc

                )

            } else if (meta is XrossDefinition.Enum) {

                init.addStatement(

                    "this.getTagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",

                    "${meta.symbolPrefix}_get_tag",

                    FunctionDescriptor::class.asTypeName(),

                    JAVA_INT,

                    ADDRESS

                )

                init.addStatement(

                    "this.getVariantNameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",

                    "${meta.symbolPrefix}_get_variant_name",

                    FunctionDescriptor::class.asTypeName(),

                    ADDRESS,

                    ADDRESS

                )

                meta.variants.forEach { v ->

                    val argLayouts = v.fields.map { CodeBlock.of("%M", it.ty.layoutMember) }

                    val desc = if (argLayouts.isEmpty()) {

                        CodeBlock.of("%T.of(%M)", FunctionDescriptor::class.asTypeName(), ADDRESS)

                    } else {

                        CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class.asTypeName(), ADDRESS, argLayouts.joinToCode(", "))

                    }

                    init.addStatement(

                        "this.new${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",

                        "${meta.symbolPrefix}_new_${v.name}",

                        desc

                    )

                }

            }

    

            meta.methods.filter { !it.isConstructor }.forEach { method ->

                val args = mutableListOf<CodeBlock>()

                if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))

                method.args.forEach { args.add(CodeBlock.of("%M", it.ty.layoutMember)) }

                

                                                val desc = if (method.ret is XrossType.Void) {

                

                                                    CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class.asTypeName(), args.joinToCode(", "))

                

                                                } else {

                

                                                    val argsPart = if (args.isEmpty()) CodeBlock.of("") else CodeBlock.of(", %L", args.joinToCode(", "))

                

                                                    val retLayout = when (method.ret) {

                

                                                        is XrossType.Result -> XROSS_RESULT_LAYOUT

                

                                                        else -> CodeBlock.of("%M", method.ret.layoutMember)

                

                                                    }

                

                                                    CodeBlock.of("%T.of(%L%L)", FunctionDescriptor::class.asTypeName(), retLayout, argsPart)

                

                                                }

                

                init.addStatement(

                    "this.${method.name.toCamelCase()}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",

                    method.symbol,

                    desc

                )

            }

        }

    

            private fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {

    

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

    

                    

    

                    val kotlinSize = when (field.ty) {

    

                        is XrossType.I32, is XrossType.F32 -> 4L

    

                        is XrossType.I64, is XrossType.F64, is XrossType.Pointer, is XrossType.RustString -> 8L

    

                        is XrossType.Bool, is XrossType.I8 -> 1L

    

                        is XrossType.I16, is XrossType.U16 -> 2L

    

                        else -> 8L

    

                    }

    

        

    

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

    

                        .endControlFlow()

    

                }

    

                

    

                init.beginControlFlow("else")

    

                    .addStatement("layouts.add(%T.paddingLayout(fSize))", MEMORY_LAYOUT)

    

                    .addStatement("currentOffsetPos = fOffset + fSize")

    

                    .endControlFlow()

    

                    

    

                init.endControlFlow() // end for parts

    

        

    

                init.beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")

    

                    .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MEMORY_LAYOUT)

    

                    .endControlFlow()

    

        

    

                init.addStatement("this.LAYOUT = if (layouts.isEmpty()) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout(*layouts.toTypedArray())", MEMORY_LAYOUT, MEMORY_LAYOUT, MEMORY_LAYOUT)

    

            }

    

        

    

        private fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {

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

                        

                        val kotlinSize = when (field.ty) {

                            is XrossType.I32, is XrossType.F32 -> 4L

                            is XrossType.I64, is XrossType.F64, is XrossType.Pointer, is XrossType.RustString -> 8L

                            is XrossType.Bool, is XrossType.I8 -> 1L

                            is XrossType.I16, is XrossType.U16 -> 2L

                            else -> 8L

                        }

    

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

                        

                                                init.addStatement(

                        

                                                    "this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL"

                        

                                                )

                        

                                                

                        

                                                if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {

                        

                                                    init.addStatement(

                        

                                                        "this.VH_${variant.name}_${field.name.toCamelCase()} = %M.varHandle()",

                        

                                                        field.ty.layoutMember

                        

                                                    )

                        

                                                }

                        

                                                

                        

                                                init.endControlFlow()

                        

                        

                        

                        

                    }

                    init.endControlFlow()

                }

                init.endControlFlow() // fInfo loop

                    .endControlFlow() // if vFields not empty

            }

            init.endControlFlow()

    

                            init.addStatement(

    

                                "this.LAYOUT = if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout()",

    

                                MEMORY_LAYOUT,

    

                                MEMORY_LAYOUT,

    

                                MEMORY_LAYOUT

    

                            )

    

                    

    

            

        }

    
}
