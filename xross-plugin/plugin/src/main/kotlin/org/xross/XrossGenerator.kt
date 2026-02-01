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
            .build()
        classBuilder.addType(memoryInfoClass)
        val memoryInfoClassName = ClassName("", "FieldMemoryInfo")

        // インスタンスフィールド
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class)
                .addModifiers(KModifier.PRIVATE).mutable(true).initializer("MemorySegment.NULL").build()
        )

        val companionBuilder = TypeSpec.companionObjectBuilder()
        val handleType = ClassName("java.lang.invoke", "MethodHandle")

        // --- フィールド・メソッドのプロパティ定義 ---
        // 基本ハンドル (new, drop, etc)
        val coreHandles = listOf("new", "drop", "clone", "layout")
        coreHandles.forEach { suffix ->
            companionBuilder.addProperty(
                PropertySpec.builder("${suffix}Handle", handleType).addModifiers(KModifier.PRIVATE).build()
            )
        }

        // カスタムメソッドのハンドル
        meta.methods.filter { !it.isConstructor }.forEach { method ->
            companionBuilder.addProperty(
                PropertySpec.builder("${method.name}Handle", handleType).addModifiers(KModifier.PRIVATE).build()
            )
        }

        // フィールドオフセット保持用
        meta.fields.forEach { field ->
            companionBuilder.addProperty(
                PropertySpec.builder("OFFSET_${field.name}", memoryInfoClassName).addModifiers(KModifier.PRIVATE)
                    .build()
            )
        }

// --- Companion Initializer (Linker & Handles) ---
        val companionInit = CodeBlock.builder()
            .addStatement("val linker = java.lang.foreign.Linker.nativeLinker()")
            .addStatement("val lookup = java.lang.foreign.SymbolLookup.loaderLookup()")
            .apply {
                // 基本ハンドルの初期化 (new, drop, etc.)
                coreHandles.forEach { suffix ->
                    val desc = if (suffix == "drop") {
                        CodeBlock.of("%T.ofVoid(%T.ADDRESS)", FunctionDescriptor::class, ValueLayout::class)
                    } else {
                        CodeBlock.of("%T.of(%T.ADDRESS)", FunctionDescriptor::class, ValueLayout::class)
                    }
                    // apply内なので直接 addStatement を呼ぶ
                    addStatement(
                        "${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        "${meta.symbolPrefix}_$suffix",
                        desc
                    )
                }

                // カスタムメソッドのハンドルの初期化
                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayoutBlocks = mutableListOf<CodeBlock>()

                    // self (ポインタ)
                    argLayoutBlocks.add(CodeBlock.of("%T.ADDRESS", ValueLayout::class))

                    // 各引数
                    method.args.forEach { arg ->
                        argLayoutBlocks.add(CodeBlock.of("%M", arg.ty.layoutMember))
                    }

                    val argsJoined = argLayoutBlocks.joinToCode(", ")

                    if (method.ret == XrossType.Void) {
                        // 直接 addStatement を呼ぶ (%L を使って CodeBlock を埋め込む)
                        addStatement(
                            "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %T.ofVoid($argsJoined))",
                            method.symbol,
                            FunctionDescriptor::class
                        )
                    } else {
                        addStatement(
                            "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %T.of(%T.%M, $argsJoined))",
                            method.symbol,
                            FunctionDescriptor::class,
                            ValueLayout::class,
                            method.ret.layoutMember
                        )
                    }
                }
            }
            .beginControlFlow("try")
            .addStatement("val layoutRaw = layoutHandle.invokeExact() as MemorySegment")
            .addStatement("val layoutStr = layoutRaw.getString(0)")
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
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(%S, e)", "Failed to initialize Rust layout")
            .endControlFlow()

        companionBuilder.addInitializerBlock(companionInit.build())

        // --- ゲッター / セッター ---
        meta.fields.forEach { field ->
            classBuilder.addProperty(
                PropertySpec.builder(field.name, field.ty.kotlinType).mutable(true)
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
                    .addKdoc(field.docs.joinToString("\n")).build()
            )
        }

        // --- メソッド実装 ---
        meta.methods.forEach { method ->
            if (method.isConstructor) {
                // Constructor: static factory method in companion
                val factory = FunSpec.builder(method.name).returns(ClassName(targetPackage, className))
                    .addKdoc(method.docs.joinToString("\n"))
                    .beginControlFlow("try")
                    .addStatement("val instance = %T()", ClassName(targetPackage, className))
                    .addStatement("instance.segment = newHandle.invokeExact() as MemorySegment")
                    .addStatement("return instance")
                    .nextControlFlow("catch (e: Throwable)")
                    .addStatement("throw RuntimeException(e)")
                    .endControlFlow()
                companionBuilder.addFunction(factory.build())
            } else {
                // Instance Method
                val funBuilder = FunSpec.builder(method.name).returns(method.ret.kotlinType)
                    .addKdoc(method.docs.joinToString("\n"))

                method.args.forEach { arg -> funBuilder.addParameter(arg.name, arg.ty.kotlinType) }

                val invokeArgs = mutableListOf("segment") // self
                method.args.forEach { invokeArgs.add(it.name) }

                funBuilder.beginControlFlow("try")
                if (method.ret == XrossType.Void) {
                    funBuilder.addStatement("${method.name}Handle.invokeExact(${invokeArgs.joinToString(", ")})")
                } else {
                    funBuilder.addStatement(
                        "return ${method.name}Handle.invokeExact(${invokeArgs.joinToString(", ")}) as %T",
                        method.ret.kotlinType
                    )
                }
                funBuilder.nextControlFlow("catch (e: Throwable)")
                    .addStatement("throw RuntimeException(e)")
                funBuilder.endControlFlow()

                classBuilder.addFunction(funBuilder.build())
            }
        }

        classBuilder.addType(companionBuilder.build())

        // --- close メソッド ---
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
            .addType(classBuilder.build()).build().writeTo(outputDir)
    }
}
