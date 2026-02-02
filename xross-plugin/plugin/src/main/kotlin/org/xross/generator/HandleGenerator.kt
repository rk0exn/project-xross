package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.*
import org.xross.helper.StringHelper.toCamelCase
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.lang.foreign.*
import java.nio.ByteOrder

object HandleGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val ADDRESS = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generateHandles(companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        val coreHandles = listOf("new", "drop", "clone", "layout", "ref", "refMut")

        // 1. ハンドルプロパティの定義 (MethodHandle)
        (coreHandles + "xross_free_string").forEach {
            companionBuilder.addProperty(PropertySpec.builder("${it}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        meta.methods.filter { !it.isConstructor }.forEach {
            companionBuilder.addProperty(
                PropertySpec.builder("${it.name}Handle", HANDLE_TYPE, KModifier.PRIVATE).build()
            )
        }

        // --- 追加: Atomic フィールド用の VarHandle プロパティ ---
        meta.fields.filter { it.safety == XrossThreadSafety.Atomic }.forEach { field ->
            companionBuilder.addProperty(
                PropertySpec.builder("VH_${field.name.toCamelCase()}", VH_TYPE).build()
            )
        }

        // 2. 共通定数
        companionBuilder.addProperty(
            PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE)
                .initializer("Cleaner.create()").build()
        )
        companionBuilder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE).mutable().initializer("0L").build()
        )

        meta.fields.forEach {
            companionBuilder.addProperty(
                PropertySpec.builder("OFFSET_${it.name}", ClassName("", "FieldMemoryInfo"), KModifier.PRIVATE).build()
            )
        }

        // 3. init ブロック
        val init = CodeBlock.builder()
            .addStatement("val linker = Linker.nativeLinker()")
            .addStatement("val lookup = SymbolLookup.loaderLookup()")
            .apply {
                // xross_free_string (文字列解放用)
                addStatement(
                    "xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), FunctionDescriptor.ofVoid(%M))",
                    ADDRESS
                )

                // Core Handles (ライフサイクル・参照管理)
                coreHandles.forEach { suffix ->
                    val symbol = "${meta.symbolPrefix}_$suffix"
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("FunctionDescriptor.ofVoid(%M)", ADDRESS)
                        "layout" -> CodeBlock.of("FunctionDescriptor.of(%M)", ADDRESS)
                        "clone", "ref", "refMut" -> CodeBlock.of("FunctionDescriptor.of(%M, %M)", ADDRESS, ADDRESS)
                        "new" -> {
                            val ctor = meta.methods.find { it.isConstructor }
                            val args = ctor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
                            if (args.isEmpty()) CodeBlock.of("FunctionDescriptor.of(%M)", ADDRESS)
                            else CodeBlock.of("FunctionDescriptor.of(%M, %L)", ADDRESS, args.joinToCode(", "))
                        }

                        else -> CodeBlock.of("FunctionDescriptor.ofVoid()")
                    }
                    // シンボルが存在しない場合に備えて try-catch や Optional チェックを入れることも可能
                    addStatement("${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", symbol, desc)
                }

                // Custom Methods
                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayouts = mutableListOf<CodeBlock>()
                    // インスタンスメソッドの場合は第一引数に Self のポインタ (ADDRESS) を追加
                    if (method.methodType != XrossMethodType.Static) {
                        argLayouts.add(CodeBlock.of("%M", ADDRESS))
                    }
                    method.args.forEach { argLayouts.add(CodeBlock.of("%M", it.ty.layoutMember)) }

                    val desc = if (method.ret is XrossType.Void) {
                        if (argLayouts.isEmpty()) CodeBlock.of("FunctionDescriptor.ofVoid()")
                        else CodeBlock.of("FunctionDescriptor.ofVoid(%L)", argLayouts.joinToCode(", "))
                    } else {
                        if (argLayouts.isEmpty()) CodeBlock.of("FunctionDescriptor.of(%M)", method.ret.layoutMember)
                        else CodeBlock.of(
                            "FunctionDescriptor.of(%M, %L)",
                            method.ret.layoutMember,
                            argLayouts.joinToCode(", ")
                        )
                    }
                    addStatement(
                        "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        method.symbol,
                        desc
                    )
                }
            }
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as MemorySegment")
            .addStatement("val layoutStr = if (layoutRaw == MemorySegment.NULL) \"\" else layoutRaw.reinterpret(1024 * 1024).getString(0)")
            .addStatement("val tempMap = layoutStr.split(';').filter { it.isNotBlank() }.associate { part -> val bits = part.split(':'); bits[0] to FieldMemoryInfo(bits[1].toLong(), bits[2].toLong()) }")
            .apply {
                meta.fields.forEach {
                    addStatement(
                        "OFFSET_${it.name} = tempMap[%S] ?: throw IllegalStateException(%S)",
                        it.name,
                        "Field ${it.name} not found"
                    )

                    // --- VarHandle の初期化 ---
                    if (it.safety == XrossThreadSafety.Atomic) {
                        val vhName = "VH_${it.name.toCamelCase()}"
                        // ValueLayout (例: JAVA_INT) は既に varHandle() メソッドを持っています
                        addStatement(
                            "$vhName = %M.varHandle()",
                            it.ty.layoutMember // JAVA_INT, JAVA_LONG などの MemberName
                        )
                    }
                }
            }
            .addStatement("STRUCT_SIZE = tempMap[\"__self\"]?.size ?: 0L")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(e)")
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }
}
