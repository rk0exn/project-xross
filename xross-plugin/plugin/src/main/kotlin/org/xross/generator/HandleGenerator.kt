package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.XrossClass
import org.xross.XrossMethodType
import org.xross.XrossType
import java.lang.invoke.MethodHandle

object HandleGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val ADDRESS = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generateHandles(
        companionBuilder: TypeSpec.Builder,
        meta: XrossClass
    ) {
        // コアハンドルのリストに参照系を追加
        // ref: &Self を取得するためのハンドル
        // refMut: &mut Self を取得するためのハンドル
        val coreHandles = listOf("new", "drop", "clone", "layout", "ref", "refMut")

        // 1. ハンドルプロパティの定義
        (coreHandles + "xross_free_string").forEach {
            companionBuilder.addProperty(
                PropertySpec.builder("${it}Handle", HANDLE_TYPE, KModifier.PRIVATE).build()
            )
        }

        meta.methods.filter { !it.isConstructor }.forEach {
            companionBuilder.addProperty(
                PropertySpec.builder("${it.name}Handle", HANDLE_TYPE, KModifier.PRIVATE).build()
            )
        }

        // 2. 共通定数とフィールドオフセット
        companionBuilder.addProperty(
            PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE)
                .initializer("Cleaner.create()")
                .build()
        )
        companionBuilder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE)
                .mutable()
                .initializer("0L")
                .build()
        )

        meta.fields.forEach {
            companionBuilder.addProperty(
                PropertySpec.builder("OFFSET_${it.name}", ClassName("", "FieldMemoryInfo"), KModifier.PRIVATE)
                    .build()
            )
        }

        // 3. init ブロック (Linker によるシンボル解決)
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
                        else CodeBlock.of("FunctionDescriptor.of(%M, %L)", method.ret.layoutMember, argLayouts.joinToCode(", "))
                    }
                    addStatement("${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)", method.symbol, desc)
                }
            }
            // 4. レイアウト解析と定数初期化
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as MemorySegment")
            .addStatement("val layoutStr = if (layoutRaw == MemorySegment.NULL) \"\" else layoutRaw.reinterpret(1024 * 1024).getString(0)")
            .addStatement(
                "val tempMap = layoutStr.split(';').filter { it.isNotBlank() }.associate { part -> " +
                        "val bits = part.split(':'); bits[0] to FieldMemoryInfo(bits[1].toLong(), bits[2].toLong()) }"
            )
            .apply {
                meta.fields.forEach {
                    addStatement("OFFSET_${it.name} = tempMap[%S] ?: throw IllegalStateException(%S)", it.name, "Field ${it.name} not found")
                }
            }
            .addStatement("STRUCT_SIZE = tempMap[\"__self\"]?.size ?: 0L")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(e)")
            .endControlFlow()

        companionBuilder.addInitializerBlock(init.build())
    }
}
