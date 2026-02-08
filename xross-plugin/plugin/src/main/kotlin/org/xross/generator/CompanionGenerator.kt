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
    private val ADDRESS = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        defineProperties(companionBuilder, meta)

        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class)
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class)

        resolveAllHandles(init, meta)

        init.add("\n// --- Native Layout Resolution ---\n")
        init.addStatement("var layoutRaw: %T = %T.NULL", MemorySegment::class, MemorySegment::class)
        init.addStatement("var layoutStr = %S", "")

        init.beginControlFlow("try")
            .addStatement("layoutRaw = layoutHandle.invokeExact() as %T", MemorySegment::class)
            .beginControlFlow("if (layoutRaw != %T.NULL)", MemorySegment::class)
            .addStatement("layoutStr = layoutRaw.reinterpret(%T.MAX_VALUE).getString(0)", Long::class)
            .endControlFlow()
            .nextControlFlow("catch (e: %T)", Throwable::class)
            .addStatement("throw %T(e)", RuntimeException::class)
            .endControlFlow()

        init.beginControlFlow("if (layoutStr.isNotEmpty())")
            .addStatement("val parts = layoutStr.split(';')")
            .addStatement("this.STRUCT_SIZE = parts[0].toLong()")

        when (meta) {
            is XrossDefinition.Struct -> buildStructLayoutInit(init, meta)
            is XrossDefinition.Enum -> buildEnumLayoutInit(init, meta)
            is XrossDefinition.Opaque -> { /* Opaque handles size/layout manually */
            }
        }

        init.beginControlFlow("if (layoutRaw != %T.NULL)", MemorySegment::class)
            .addStatement("xross_free_stringHandle.invokeExact(layoutRaw)")
            .endControlFlow()
            .nextControlFlow("else")
            .addStatement("this.STRUCT_SIZE = 0L")
            .addStatement("this.LAYOUT = %T.structLayout()", MemoryLayout::class)
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }

    private fun defineProperties(builder: TypeSpec.Builder, meta: XrossDefinition) {
        val handles = mutableListOf("dropHandle", "cloneHandle", "layoutHandle", "xross_free_stringHandle")

        when (meta) {
            is XrossDefinition.Struct -> {
                handles.add("newHandle")
                meta.fields.forEach {
                    builder.addProperty(
                        PropertySpec.builder(
                            "VH_${it.name.toCamelCase()}", VH_TYPE, KModifier.INTERNAL,
                            KModifier.LATEINIT
                        ).mutable().build()
                    )
                }
            }

            is XrossDefinition.Enum -> {
                handles.add("get_tagHandle")
                handles.add("get_variant_nameHandle")
                meta.variants.forEach { v ->
                    handles.add("new_${v.name}Handle")
                    v.fields.forEach { f ->
                        val baseCamel = f.name.toCamelCase()
                        builder.addProperty(
                            PropertySpec.builder("VH_${v.name}_$baseCamel",
                                VH_TYPE,
                                KModifier.INTERNAL, KModifier.LATEINIT
                            ).mutable().build()
                        )
                        builder.addProperty(
                            PropertySpec.builder(
                                "OFFSET_${v.name}_$baseCamel",
                                Long::class, KModifier.INTERNAL
                            ).mutable().initializer("0L").build()
                        )
                    }
                }
            }

            is XrossDefinition.Opaque -> { /* No additional fields for Opaque here */
            }
        }

        meta.methods.filter { !it.isConstructor }.forEach { handles.add("${it.name}Handle") }

        handles.distinct().forEach { name ->
            builder.addProperty(PropertySpec.builder(name, HANDLE_TYPE, KModifier.INTERNAL).mutable().build())
        }

        builder.addProperty(
            PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.PRIVATE).mutable()
                .build()
        )
        builder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.INTERNAL).mutable().initializer("0L").build()
        )
    }

    private fun resolveAllHandles(init: CodeBlock.Builder, meta: XrossDefinition) {
        init.addStatement(
            "this.xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))",
            FunctionDescriptor::class,
            ADDRESS
        )

        listOf("drop", "layout", "clone").forEach { suffix ->
            val symbol = "${meta.symbolPrefix}_$suffix"
            val desc = when (suffix) {
                "drop" -> CodeBlock.of("%T.ofVoid(%M)", FunctionDescriptor::class, ADDRESS)
                "layout" -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                else -> CodeBlock.of("%T.of(%M, %M)", FunctionDescriptor::class, ADDRESS, ADDRESS)
            }
            init.addStatement("this.${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
        }

        if (meta is XrossDefinition.Struct) {
            val constructor = meta.methods.find { it.isConstructor && it.name == "new" }
            val argLayouts = constructor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
            val desc = if (argLayouts.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
            else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
            init.addStatement(
                "this.newHandle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                "${meta.symbolPrefix}_new",
                desc
            )
        } else if (meta is XrossDefinition.Enum) {
            init.addStatement(
                "this.get_tagHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(JAVA_INT, %M))",
                "${meta.symbolPrefix}_get_tag",
                FunctionDescriptor::class,
                ADDRESS
            )
            init.addStatement(
                "this.get_variant_nameHandle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%M, %M))",
                "${meta.symbolPrefix}_get_variant_name",
                FunctionDescriptor::class,
                ADDRESS,
                ADDRESS
            )
            meta.variants.forEach { v ->
                val argLayouts = v.fields.map { CodeBlock.of("%M", it.ty.layoutMember) }
                val desc = if (argLayouts.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, argLayouts.joinToCode(", "))
                init.addStatement(
                    "this.new_${v.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                    "${meta.symbolPrefix}_new_${v.name}",
                    desc
                )
            }
        }

        meta.methods.filter { !it.isConstructor }.forEach { method ->
            val args = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) args.add(CodeBlock.of("%M", ADDRESS))
            method.args.forEach { args.add(CodeBlock.of("%M", it.ty.layoutMember)) }
            val desc = if (method.ret is XrossType.Void) CodeBlock.of(
                "%T.ofVoid(%L)",
                FunctionDescriptor::class,
                args.joinToCode(", ")
            )
            else CodeBlock.of(
                "%T.of(%M%L)",
                FunctionDescriptor::class,
                method.ret.layoutMember,
                if (args.isEmpty()) "" else ", " + args.joinToCode(", ")
            )
            init.addStatement(
                "this.${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                method.symbol,
                desc
            )
        }
    }

    // CompanionGenerator.kt 内の buildStructLayoutInit を修正
    private fun buildStructLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Struct) {
        init.addStatement("val layouts = mutableListOf<%T>()", MemoryLayout::class)
        init.addStatement("var currentOffsetPos = 0L")

        // レイアウト文字列のパースループ
        init.beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val f = parts[i].split(':')")
            .addStatement("if (f.size < 3) continue")
            .addStatement("val fName = f[0]; val fOffset = f[1].toLong(); val fSize = f[2].toLong()")
            .beginControlFlow("if (fOffset > currentOffsetPos)")
            .addStatement("layouts.add(%T.paddingLayout(fOffset - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()

        // 各フィールド名と一致した時にレイアウトを登録する
        meta.fields.forEachIndexed { idx, field ->
            val branch = if (idx == 0) "if" else "else if"
            init.beginControlFlow("$branch (fName == %S)", field.name)
            
            if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                // Inline struct: use padding layout of the reported size
                init.addStatement("layouts.add(%T.paddingLayout(fSize).withName(%S))", MemoryLayout::class, field.name)
            } else {
                init.addStatement("layouts.add(%M.withName(%S))", field.ty.layoutMember, field.name)
            }
            
            init.addStatement("currentOffsetPos = fOffset + fSize")
                .endControlFlow()
        }
        init.endControlFlow()

        init.beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")
            .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()

        // LAYOUT の確定
        init.addStatement("this.LAYOUT = %T.structLayout(*layouts.toTypedArray())", MemoryLayout::class)

        // 【重要】各フィールドの VarHandle を初期化するコードを生成
        meta.fields.forEach { field ->
            if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                init.addStatement(
                    "this.VH_${field.name.toCamelCase()} = LAYOUT.varHandle(%T.PathElement.groupElement(%S))",
                    MemoryLayout::class, field.name
                )
            }
        }
    }

    private fun buildEnumLayoutInit(init: CodeBlock.Builder, meta: XrossDefinition.Enum) {
        init.addStatement("val variantRegex = %T(%S)", Regex::class, "(\\w+)(?:\\{(.*)\\})?")
            .beginControlFlow("for (i in 1 until parts.size)")
            .addStatement("val match = variantRegex.find(parts[i]) ?: continue")
            .addStatement("val vName = match.groupValues[1]")
            .addStatement("val vFields = match.groupValues[2]")

        init.beginControlFlow("if (vFields.isNotEmpty())")
            .beginControlFlow("for (fInfo in vFields.split(';'))")
            .addStatement("if (fInfo.isBlank()) continue")
            .addStatement("val f = fInfo.split(':')")
            .addStatement("val fName = f[0]; val fOffsetL = f[1].toLong(); val fSizeL = f[2].toLong()")

        meta.variants.forEach { variant ->
            init.beginControlFlow("if (vName == %S)", variant.name)
            variant.fields.forEach { field ->
                init.beginControlFlow("if (fName == %S)", field.name)
                    .addStatement("val vLayouts = mutableListOf<%T>()", MemoryLayout::class)
                    // Rust側の offset_of! は Enum 先頭からの絶対オフセットなので、それをそのままパディングに使用
                    .addStatement("if (fOffsetL > 0) vLayouts.add(%T.paddingLayout(fOffsetL))", MemoryLayout::class)
                
                if (field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned) {
                    init.addStatement("vLayouts.add(%T.paddingLayout(fSizeL).withName(fName))", MemoryLayout::class)
                } else {
                    init.addStatement("vLayouts.add(%M.withName(fName).withByteAlignment(1))", field.ty.layoutMember)
                }
                
                // Enum全体のサイズに合わせるための末尾パディング
                init.addStatement("val remaining = STRUCT_SIZE - fOffsetL - fSizeL")
                    .addStatement("if (remaining > 0) vLayouts.add(%T.paddingLayout(remaining))", MemoryLayout::class)
                    .addStatement("val vLayout = %T.structLayout(*vLayouts.toTypedArray())", MemoryLayout::class)
                
                if (!(field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned)) {
                    init.addStatement(
                        "this.VH_${variant.name}_${field.name.toCamelCase()} = vLayout.varHandle(%T.PathElement.groupElement(fName))",
                        MemoryLayout::class
                    )
                }
                
                init.addStatement(
                    "this.OFFSET_${variant.name}_${field.name.toCamelCase()} = fOffsetL"
                )
                    .endControlFlow()
            }
            init.endControlFlow()
        }
        init.endControlFlow() // fInfo loop
            .endControlFlow() // if vFields not empty
            .endControlFlow() // parts loop

        init.addStatement(
            "this.LAYOUT = if (STRUCT_SIZE > 0) %T.structLayout(%T.paddingLayout(STRUCT_SIZE)) else %T.structLayout()",
            MemoryLayout::class,
            MemoryLayout::class,
            MemoryLayout::class
        )
    }
}