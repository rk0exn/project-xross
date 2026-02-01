package org.xross

import com.squareup.kotlinpoet.*
import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

object XrossGenerator {
    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val className = meta.structName
        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(AutoCloseable::class)

        // --- 内部クラス: フィールド情報 ---
        val memoryInfoClass = TypeSpec.classBuilder("FieldMemoryInfo")
            .addModifiers(KModifier.PRIVATE)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("offset", Long::class)
                    .addParameter("size", Long::class).build()
            )
            .addProperty(PropertySpec.builder("offset", Long::class).initializer("offset").build())
            .addProperty(PropertySpec.builder("size", Long::class).initializer("size").build())
            .build()
        classBuilder.addType(memoryInfoClass)
        val memoryInfoClassName = ClassName("", "FieldMemoryInfo")

        // インスタンスフィールド
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class)
                .addModifiers(KModifier.PRIVATE).mutable(true).initializer("MemorySegment.NULL").build()
        )

        // --- コンストラクタ生成 ---
        val constructorMethod = meta.methods.find { it.isConstructor }
        if (constructorMethod != null) {
            val constructorBuilder = FunSpec.constructorBuilder()
            constructorMethod.args.forEach { arg ->
                constructorBuilder.addParameter(arg.escapeName(), arg.ty.kotlinType)
            }

            val invokeArgs = constructorMethod.args.joinToString(", ") { it.escapeName() }

            constructorBuilder.beginControlFlow("try")
                // ★ 修正：戻ってきたポインタを STRUCT_SIZE で reinterpret する
                .addStatement("val raw = newHandle.invokeExact($invokeArgs) as MemorySegment")
                .addStatement("this.segment = if (STRUCT_SIZE > 0) raw.reinterpret(STRUCT_SIZE) else raw")
                .nextControlFlow("catch (e: Throwable)")
                .addStatement("throw RuntimeException(%S, e)", "Failed to allocate native struct")
                .endControlFlow()

            classBuilder.primaryConstructor(constructorBuilder.build())
        }

        // --- Companion Object ---
        val companionBuilder = TypeSpec.companionObjectBuilder()
        val handleType = java.lang.invoke.MethodHandle::class.asClassName()

        // ハンドル定義
        val coreHandles = listOf("new", "drop", "clone", "layout")
        coreHandles.forEach { suffix ->
            companionBuilder.addProperty(
                PropertySpec.builder("${suffix}Handle", handleType).addModifiers(KModifier.PRIVATE).build()
            )
        }
        meta.methods.filter { !it.isConstructor }.forEach { method ->
            companionBuilder.addProperty(
                PropertySpec.builder("${method.name}Handle", handleType).addModifiers(KModifier.PRIVATE).build()
            )
        }

        // ★ 構造体全体のサイズを保持
        companionBuilder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class).addModifiers(KModifier.PRIVATE).mutable(true)
                .initializer("0L").build()
        )

        meta.fields.forEach { field ->
            companionBuilder.addProperty(
                PropertySpec.builder("OFFSET_${field.name}", memoryInfoClassName).addModifiers(KModifier.PRIVATE)
                    .build()
            )
        }

        // --- Initializer Block ---
        val companionInit = CodeBlock.builder()
            .addStatement("val linker = Linker.nativeLinker()")
            .addStatement("val lookup = SymbolLookup.loaderLookup()")
            .apply {
                coreHandles.forEach { suffix ->
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("%T.ofVoid(ValueLayout.ADDRESS)", FunctionDescriptor::class)
                        "new" -> {
                            val constructorArgs =
                                constructorMethod?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
                            CodeBlock.of(
                                "%T.of(ValueLayout.ADDRESS, %L)",
                                FunctionDescriptor::class,
                                constructorArgs.joinToCode(", ")
                            )
                        }

                        else -> CodeBlock.of("%T.of(ValueLayout.ADDRESS)", FunctionDescriptor::class)
                    }
                    addStatement(
                        "${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        "${meta.symbolPrefix}_$suffix",
                        desc
                    )
                }

                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayouts = mutableListOf(CodeBlock.of("ValueLayout.ADDRESS"))
                    method.args.forEach { arg -> argLayouts.add(CodeBlock.of("%M", arg.ty.layoutMember)) }
                    if (method.ret == XrossType.Void) {
                        addStatement(
                            "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid(${
                                argLayouts.joinToCode(
                                    ", "
                                )
                            }))", method.symbol, FunctionDescriptor::class
                        )
                    } else {
                        addStatement(
                            "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%T.%M, ${
                                argLayouts.joinToCode(
                                    ", "
                                )
                            }))", method.symbol, FunctionDescriptor::class, ValueLayout::class, method.ret.layoutMember
                        )
                    }
                }
            }
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as MemorySegment")
            // ★ メタデータ読み込みも reinterpret
            .addStatement("val layoutStr = if (layoutRaw == MemorySegment.NULL) \"\" else layoutRaw.reinterpret(1024 * 1024).getString(0)")
            .addStatement("val tempMap = layoutStr.split(';').filter { it.isNotBlank() }.associate { part ->")
            .addStatement("    val bits = part.split(':')")
            .addStatement("    bits[0] to FieldMemoryInfo(bits[1].toLong(), bits[2].toLong())")
            .addStatement("}")
            .apply {
                meta.fields.forEach { field ->
                    addStatement(
                        "OFFSET_${field.name} = tempMap[%S] ?: throw IllegalStateException(%S)",
                        field.name,
                        "Field offset not found"
                    )
                }
            }
            // ★ 構造体全体のサイズを計算（最後のフィールドの offset + size）
            .addStatement("STRUCT_SIZE = tempMap.values.maxOfOrNull { it.offset + 8 } ?: 0L") // 簡易的に最大オフセット+8バイト
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(%S, e)", "Failed to initialize Rust layout")
            .endControlFlow()

        companionBuilder.addInitializerBlock(companionInit.build())

        // --- フィールド (ゲッター/セッター) ---
        meta.fields.forEach { field ->
            classBuilder.addProperty(
                PropertySpec.builder(field.escapeName(), field.ty.kotlinType).mutable(true)
                    .getter(
                        FunSpec.getterBuilder().addStatement(
                            "return segment.get(%T.%M, OFFSET_${field.name}.offset)",
                            ValueLayout::class,
                            field.ty.layoutMember
                        ).build()
                    )
                    .setter(
                        FunSpec.setterBuilder().addParameter("value", field.ty.kotlinType)
                            .addStatement(
                                "segment.set(%T.%M, OFFSET_${field.name}.offset, value)",
                                ValueLayout::class,
                                field.ty.layoutMember
                            ).build()
                    )
                    .build()
            )
        }

        // --- インスタンスメソッド ---
        meta.methods.filter { !it.isConstructor }.forEach { method ->
            val funBuilder = FunSpec.builder(method.name).returns(method.ret.kotlinType)
            method.args.forEach { arg -> funBuilder.addParameter(arg.escapeName(), arg.ty.kotlinType) }
            val invokeArgs = (listOf("segment") + method.args.map { it.escapeName() }).joinToString(", ")
            funBuilder.beginControlFlow("try")
            if (method.ret == XrossType.Void) {
                funBuilder.addStatement("${method.name}Handle.invokeExact($invokeArgs)")
            } else {
                funBuilder.addStatement(
                    "return ${method.name}Handle.invokeExact($invokeArgs) as %T",
                    method.ret.kotlinType
                )
            }
            funBuilder.nextControlFlow("catch (e: Throwable)")
                .addStatement("throw RuntimeException(e)")
            funBuilder.endControlFlow()
            classBuilder.addFunction(funBuilder.build())
        }

        classBuilder.addType(companionBuilder.build())

        // --- Close ---
        classBuilder.addFunction(
            FunSpec.builder("close").addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != MemorySegment.NULL)")
                .beginControlFlow("try")
                .addStatement("dropHandle.invokeExact(segment)")
                .nextControlFlow("catch (e: Throwable)")
                .addStatement("throw RuntimeException(e)")
                .endControlFlow()
                .addStatement("segment = MemorySegment.NULL")
                .endControlFlow().build()
        )

        FileSpec.builder(targetPackage, className).indent("    ")
            .addImport(
                "java.lang.foreign",
                "ValueLayout",
                "FunctionDescriptor",
                "MemorySegment",
                "Linker",
                "SymbolLookup"
            )
            .addType(classBuilder.build()).build().writeTo(outputDir)
    }
}

private val KOTLIN_KEYWORDS = setOf(
    "package",
    "as",
    "typealias",
    "class",
    "this",
    "super",
    "val",
    "var",
    "fun",
    "for",
    "is",
    "in",
    "throw",
    "return",
    "break",
    "continue",
    "object",
    "if",
    "else",
    "while",
    "do",
    "try",
    "when",
    "interface",
    "typeof"
)

private fun XrossField.escapeName(): String = if (this.name in KOTLIN_KEYWORDS) "`${this.name}`" else this.name
