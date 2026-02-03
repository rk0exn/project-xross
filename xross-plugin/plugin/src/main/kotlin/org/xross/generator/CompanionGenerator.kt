package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethodType
import org.xross.structures.XrossType
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.StampedLock

object CompanionGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val LAYOUT_TYPE = StructLayout::class.asClassName()
    private val ADDRESS = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        val coreHandles = listOf("new", "drop", "clone", "layout", "ref", "refMut")

        // 1. プロパティ定義 (MethodHandle, Layout, VarHandle, etc.)
        (coreHandles + "xross_free_string").forEach {
            companionBuilder.addProperty(PropertySpec.builder("${it}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        meta.methods.filter { !it.isConstructor }.forEach {
            companionBuilder.addProperty(PropertySpec.builder("${it.name}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        companionBuilder.addProperty(PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.PRIVATE).build())
        meta.fields.forEach { field ->
            companionBuilder.addProperty(PropertySpec.builder("VH_${field.name.toCamelCase()}", VH_TYPE, KModifier.PRIVATE).build())
        }
        companionBuilder.addProperty(PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE).mutable().initializer("0L").build())

        if (meta.methods.any { it.methodType == XrossMethodType.Static }) {
            companionBuilder.addProperty(PropertySpec.builder("sl", StampedLock::class, KModifier.PRIVATE).initializer("%T()", StampedLock::class).build())
        }
        companionBuilder.addProperty(PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE).initializer("Cleaner.create()").build())

        // 4. init ブロック
        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class)
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class)
            .apply {
                // xross_free_string
                addStatement("xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))", FunctionDescriptor::class, ADDRESS)

                // Core Handles
                coreHandles.forEach { suffix ->
                    val symbol = "${meta.symbolPrefix}_$suffix"
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("%T.ofVoid(%M)", FunctionDescriptor::class, ADDRESS)
                        "layout" -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                        "clone", "ref", "refMut" -> CodeBlock.of("%T.of(%M, %M)", FunctionDescriptor::class, ADDRESS, ADDRESS)
                        "new" -> {
                            val ctor = meta.methods.find { it.isConstructor }
                            val args = ctor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
                            if (args.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                            else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, ADDRESS, args.joinToCode(", "))
                        }
                        else -> CodeBlock.of("%T.ofVoid()", FunctionDescriptor::class)
                    }
                    addStatement("${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
                }

                // Custom Methods (末尾コンマ対策)
                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayouts = mutableListOf<CodeBlock>()
                    if (method.methodType != XrossMethodType.Static) argLayouts.add(CodeBlock.of("%M", ADDRESS))
                    method.args.forEach { argLayouts.add(CodeBlock.of("%M", it.ty.layoutMember)) }

                    val desc = if (method.ret is XrossType.Void) {
                        if (argLayouts.isEmpty()) CodeBlock.of("%T.ofVoid()", FunctionDescriptor::class)
                        else CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class, argLayouts.joinToCode(", "))
                    } else {
                        if (argLayouts.isEmpty()) CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, method.ret.layoutMember)
                        else CodeBlock.of("%T.of(%M, %L)", FunctionDescriptor::class, method.ret.layoutMember, argLayouts.joinToCode(", "))
                    }
                    addStatement("${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", method.symbol, desc)
                }
            }
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as %T", MemorySegment::class)
            .addStatement("val layoutStr = if (layoutRaw == %T.NULL) \"\" else layoutRaw.reinterpret(%T.MAX_VALUE).getString(0)", MemorySegment::class, Long::class)
            .addStatement("val parts = layoutStr.split(';').filter { it.isNotBlank() }")
            .addStatement("data class TempField(val name: String, val offset: Long, val size: Long)")
            .addStatement("val tempFields = parts.map { val b = it.split(':'); TempField(b[0], b[1].toLong(), b[2].toLong()) }")
            .addStatement("val selfInfo = tempFields.find { it.name == \"__self\" } ?: throw %T(\"Missing __self metadata\")", IllegalStateException::class)
            .addStatement("STRUCT_SIZE = selfInfo.size")
            .addStatement("val sortedFields = tempFields.filter { it.name != \"__self\" }.sortedBy { it.offset }")

            .addStatement("val layouts = mutableListOf<%T>()", MemoryLayout::class)
            .addStatement("var currentOffsetPos = 0L") // 警告回避のため名前を変更し、確実に更新
            .beginControlFlow("for (f in sortedFields)")
            .beginControlFlow("if (f.offset > currentOffsetPos)")
            .addStatement("layouts.add(%T.paddingLayout(f.offset - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()
            .apply {
                // フィールドごとの型を決定し、currentOffsetPos を更新するロジック
                // if-else ではなく、名前解決を確実に行う
                meta.fields.forEach { field ->
                    beginControlFlow("if (f.name == %S)", field.name)
                    addStatement("layouts.add(%M.withName(%S))", field.ty.layoutMember, field.name)
                    // ここで currentOffsetPos を更新
                    addStatement("currentOffsetPos = f.offset + f.size")
                    endControlFlow()
                }
            }
            // meta.fields にない、Rust 側だけの内部フィールドがある場合も currentOffsetPos を進める必要がある
            .addStatement("if (currentOffsetPos < f.offset + f.size) currentOffsetPos = f.offset + f.size")
            .endControlFlow()

            .beginControlFlow("if (currentOffsetPos < STRUCT_SIZE)")
            .addStatement("layouts.add(%T.paddingLayout(STRUCT_SIZE - currentOffsetPos))", MemoryLayout::class)
            .endControlFlow()
            .addStatement("LAYOUT = %T.structLayout(*layouts.toTypedArray())", MemoryLayout::class)

            .apply {
                meta.fields.forEach { field ->
                    addStatement("VH_${field.name.toCamelCase()} = LAYOUT.varHandle(%T.PathElement.groupElement(%S))", MemoryLayout::class, field.name)
                }
            }
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw %T(e)", RuntimeException::class)
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }
}
