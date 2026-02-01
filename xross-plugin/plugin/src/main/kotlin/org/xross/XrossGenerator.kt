package org.xross

import com.squareup.kotlinpoet.*
import java.io.File
import java.lang.foreign.*
import java.lang.invoke.MethodHandle

object XrossGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val CORE_HANDLES = listOf("new", "drop", "clone", "layout")
    private val ADDRESS_LAYOUT = MemberName("java.lang.foreign.ValueLayout", "ADDRESS")

    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val className = meta.structName
        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(AutoCloseable::class)

        classBuilder.addType(buildFieldMemoryInfoType())

        // 1. プライマリコンストラクタを private constructor(raw: MemorySegment) に設定
        // これにより init { ... } は全てのルートで共通して実行される
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("raw", MemorySegment::class)
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class)
                .addModifiers(KModifier.PRIVATE).mutable(true)
                .initializer("MemorySegment.NULL")
                .build()
        )

        // initブロックでセグメントの reinterpret を行う
        classBuilder.addInitializerBlock(
            CodeBlock.builder()
                .addStatement("this.segment = if (raw != MemorySegment.NULL && STRUCT_SIZE > 0) raw.reinterpret(STRUCT_SIZE) else raw")
                .build()
        )

        // 2. 公開用のセカンダリコンストラクタ (newを叩く)
        generatePublicConstructor(classBuilder, meta)

        val companionBuilder = generateCompanionBuilder(meta)
        generateClone(classBuilder, meta)

        // 4. フィールドとメソッド
        generateFields(classBuilder, meta.fields)
        generateMethods(classBuilder, companionBuilder, meta)

        classBuilder.addType(companionBuilder.build())
        generateCloseMethod(classBuilder)

        FileSpec.builder(targetPackage, className)
            .indent("    ")
            .addImport(
                "java.lang.foreign",
                "ValueLayout",
                "FunctionDescriptor",
                "MemorySegment",
                "Linker",
                "SymbolLookup",
                "Arena"
            )
            .addImport("java.util.concurrent.locks", "ReentrantLock")
            .addImport("kotlin.concurrent", "withLock")
            .addType(classBuilder.build())
            .build()
            .writeTo(outputDir)
    }

    private fun generatePublicConstructor(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val ctorMeta = meta.methods.find { it.isConstructor } ?: return
        val builder = FunSpec.constructorBuilder()
        ctorMeta.args.forEach { builder.addParameter(it.name.toCamelCase(), it.ty.kotlinType) }
        val invokeArgs = ctorMeta.args.joinToString(", ") { it.name.toCamelCase().escapeName() }
        builder.callThisConstructor(
            CodeBlock.of(
                "(newHandle.invokeExact($invokeArgs) as %T)",
                MemorySegment::class
            )
        )
        // もしアロケーション失敗時の独自メッセージを出したい場合は、
        // 以下のような「staticな一括生成メソッド」を挟むのが最もクリーンです
        classBuilder.addFunction(builder.build())
    }
    private fun generateClone(
        classBuilder: TypeSpec.Builder,
        meta: XrossClass
    ) {
        val className = meta.structName
        // clone: インスタンスを複製
        val cloneFun = FunSpec.builder("clone")
            .returns(ClassName("", className))
            .addKdoc("Creates a copy of this instance using the underlying native clone function.")
            .addStatement("val currentSegment = segment")
            .beginControlFlow("if (currentSegment == MemorySegment.NULL)")
            .addStatement("throw NullPointerException(%S)", "Cannot clone a dropped object")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("val newRaw = cloneHandle.invokeExact(currentSegment) as MemorySegment")
            .addStatement("return $className(newRaw)")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(%S, e)", "Failed to clone $className")
            .endControlFlow()
            .build()
        classBuilder.addFunction(cloneFun)
    }

    private fun generateFields(classBuilder: TypeSpec.Builder, fields: List<XrossField>) {
        fields.forEach { field ->
            val camelName = field.name.toCamelCase()
            val prop = PropertySpec.builder(camelName, field.ty.kotlinType).mutable(true)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return segment.get(%M, OFFSET_${field.name}.offset)", field.ty.layoutMember)
                        .build()
                )
                .setter(
                    FunSpec.setterBuilder().addParameter("value", field.ty.kotlinType)
                        .addStatement("segment.set(%M, OFFSET_${field.name}.offset, value)", field.ty.layoutMember)
                        .build()
                )
                .build()
            classBuilder.addProperty(prop)
        }
    }

    private fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        val methods = meta.methods.filter { !it.isConstructor }

        if (methods.any { it.methodType == XrossMethodType.MutInstance }) {
            classBuilder.addProperty(
                PropertySpec.builder("lock", ClassName("java.util.concurrent.locks", "ReentrantLock"))
                    .addModifiers(KModifier.PRIVATE, KModifier.FINAL)
                    .initializer("ReentrantLock()")
                    .build()
            )
        }

        methods.forEach { method ->
            val isStringRet = method.ret is XrossType.StringType
            val returnType = if (isStringRet) String::class.asTypeName() else method.ret.kotlinType
            val funBuilder = FunSpec.builder(method.name.toCamelCase()).returns(returnType)

            method.args.forEach { funBuilder.addParameter(it.name.toCamelCase(), it.ty.kotlinType) }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = segment")
                body.beginControlFlow("if (currentSegment == MemorySegment.NULL)")
                    .addStatement("throw NullPointerException(%S)", "Object has been dropped or ownership transferred")
                    .endControlFlow()
            }

            body.beginControlFlow("try")
            val needsArena = method.args.any { it.ty is XrossType.StringType || it.ty is XrossType.Slice }
            if (needsArena) body.beginControlFlow("Arena.ofConfined().use { arena ->")

            val invokeArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) invokeArgs.add("currentSegment")

            method.args.forEach { arg ->
                val argCamel = arg.name.toCamelCase()
                when (val ty = arg.ty) {
                    is XrossType.StringType -> {
                        body.addStatement("val ${argCamel}Seg = arena.allocateFrom($argCamel)")
                        invokeArgs.add("${argCamel}Seg")
                    }

                    is XrossType.Slice -> {
                        body.addStatement("val ${argCamel}Data = arena.allocateArray(${ty.elementType.layoutMember}, $argCamel.size.toLong())")
                        body.addStatement("MemorySegment.copy($argCamel, 0, ${argCamel}Data, 0, $argCamel.byteSize())")
                        body.addStatement("val ${argCamel}Slice = arena.allocate(16, 8)")
                        body.addStatement("${argCamel}Slice.set(%M, 0, ${argCamel}Data)", ADDRESS_LAYOUT)
                        body.addStatement("${argCamel}Slice.set(ValueLayout.JAVA_LONG, 8, $argCamel.size.toLong())")
                        invokeArgs.add("${argCamel}Slice")
                    }

                    else -> invokeArgs.add(argCamel)
                }
            }

            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString()})"
            val executeBlock = if (method.methodType == XrossMethodType.MutInstance) {
                CodeBlock.of("lock.withLock { %L }", call)
            } else {
                CodeBlock.of("%L", call)
            }

            when {
                method.ret is XrossType.Void -> body.addStatement("%L", executeBlock)
                isStringRet -> {
                    body.addStatement("val res = %L as MemorySegment", executeBlock)
                    body.addStatement("if (res == MemorySegment.NULL) return \"\"")
                    body.addStatement("val str = res.reinterpret(Long.MAX_VALUE).getString(0)")
                    body.addStatement("xross_free_stringHandle.invokeExact(res)")
                    body.addStatement("return str")
                }

                else -> body.addStatement("return %L as %T", executeBlock, returnType)
            }

            if (needsArena) body.endControlFlow()
            if (method.methodType == XrossMethodType.OwnedInstance) body.addStatement("segment = MemorySegment.NULL")
            body.nextControlFlow("catch (e: Throwable)").addStatement("throw RuntimeException(e)").endControlFlow()

            funBuilder.addCode(body.build())
            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun generateCompanionBuilder(meta: XrossClass): TypeSpec.Builder {
        val builder = TypeSpec.companionObjectBuilder()
        (CORE_HANDLES + "xross_free_string").forEach {
            builder.addProperty(PropertySpec.builder("${it}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        meta.methods.filter { !it.isConstructor }.forEach {
            builder.addProperty(PropertySpec.builder("${it.name}Handle", HANDLE_TYPE, KModifier.PRIVATE).build())
        }
        builder.addProperty(
            PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.PRIVATE).mutable().initializer("0L").build()
        )
        meta.fields.forEach {
            builder.addProperty(
                PropertySpec.builder(
                    "OFFSET_${it.name}",
                    ClassName("", "FieldMemoryInfo"),
                    KModifier.PRIVATE
                ).build()
            )
        }

        val init = CodeBlock.builder()
            .addStatement("val linker = Linker.nativeLinker()")
            .addStatement("val lookup = SymbolLookup.loaderLookup()")
            .apply {
                addStatement(
                    "xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(ValueLayout.ADDRESS))",
                    FunctionDescriptor::class
                )
                CORE_HANDLES.forEach { suffix ->
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("%T.ofVoid(ValueLayout.ADDRESS)", FunctionDescriptor::class)
                        "new" -> {
                            val ctor = meta.methods.find { it.isConstructor }
                            val args =
                                ctor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) }?.joinToCode() ?: ""
                            CodeBlock.of("%T.of(ValueLayout.ADDRESS, %L)", FunctionDescriptor::class, args)
                        }

                        "clone" -> {
                            // 修正: clone は自身のポインタ (ADDRESS) を引数に取り、新しいポインタを返す
                            CodeBlock.of(
                                "%T.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)",
                                FunctionDescriptor::class
                            )
                        }

                        "layout" -> {
                            CodeBlock.of("%T.of(ValueLayout.ADDRESS)", FunctionDescriptor::class)
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
                    val argLayouts = mutableListOf<CodeBlock>()
                    if (method.methodType != XrossMethodType.Static) argLayouts.add(CodeBlock.of("ValueLayout.ADDRESS"))
                    method.args.forEach { arg -> argLayouts.add(CodeBlock.of("%M", arg.ty.layoutMember)) }
                    val desc = if (method.ret is XrossType.Void) CodeBlock.of(
                        "%T.ofVoid(%L)",
                        FunctionDescriptor::class,
                        argLayouts.joinToCode()
                    )
                    else CodeBlock.of(
                        "%T.of(%M, %L)",
                        FunctionDescriptor::class,
                        method.ret.layoutMember,
                        argLayouts.joinToCode()
                    )
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
            .addStatement("val tempMap = layoutStr.split(';').filter { it.isNotBlank() }.associate { part ->")
            .addStatement("    val bits = part.split(':')")
            .addStatement("    bits[0] to FieldMemoryInfo(bits[1].toLong(), bits[2].toLong())")
            .addStatement("}")
            .apply {
                meta.fields.forEach {
                    addStatement(
                        "OFFSET_${it.name} = tempMap[%S] ?: throw IllegalStateException(\"Field ${it.name} not found\")",
                        it.name
                    )
                }
            }
            .addStatement("STRUCT_SIZE = tempMap.values.maxOfOrNull { it.offset + 8 } ?: 0L")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(\"Init failed for ${meta.structName}\", e)").endControlFlow()

        return builder.addInitializerBlock(init.build())
    }

    private fun generateCloseMethod(classBuilder: TypeSpec.Builder) {
        classBuilder.addFunction(
            FunSpec.builder("close").addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != MemorySegment.NULL)")
                .addStatement("try { dropHandle.invokeExact(segment) } catch (e: Throwable) { throw RuntimeException(e) }")
                .addStatement("segment = MemorySegment.NULL")
                .endControlFlow().build()
        )
    }

    private fun buildFieldMemoryInfoType() = TypeSpec.classBuilder("FieldMemoryInfo")
        .addModifiers(KModifier.PRIVATE).primaryConstructor(
            FunSpec.constructorBuilder().addParameter("offset", Long::class).addParameter("size", Long::class)
                .build()
        )
        .addProperty(PropertySpec.builder("offset", Long::class).initializer("offset").build())
        .addProperty(PropertySpec.builder("size", Long::class).initializer("size").build()).build()

    private fun String.toCamelCase(): String {
        val parts = this.split("_")
        return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }
    }

    private fun String.escapeName(): String = if (this in KOTLIN_KEYWORDS) "`$this`" else this

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
}
