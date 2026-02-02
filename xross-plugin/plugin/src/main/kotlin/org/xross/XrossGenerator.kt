package org.xross

import com.squareup.kotlinpoet.*
import java.io.File
import java.lang.foreign.*
import java.lang.invoke.MethodHandle

object XrossGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val CORE_HANDLES = listOf("new", "drop", "clone", "layout")

    // MemberNameの定義 (これらを%Mで使用する)
    private const val VALUE_LAYOUT = "java.lang.foreign.ValueLayout"
    private val ADDRESS = MemberName(VALUE_LAYOUT, "ADDRESS")
    private val JAVA_LONG = MemberName(VALUE_LAYOUT, "JAVA_LONG")
    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val className = meta.structName
        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(AutoCloseable::class)

        classBuilder.addType(buildFieldMemoryInfoType())

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

        val initializerBuilder = CodeBlock.builder()
            .addStatement("this.segment = if (raw != MemorySegment.NULL && STRUCT_SIZE > 0) raw.reinterpret(STRUCT_SIZE) else raw")

        generatePublicConstructor(classBuilder, meta)

        val companionBuilder = generateCompanionBuilder(meta)
        generateClone(classBuilder, meta)
        generateCleaner(classBuilder, companionBuilder, initializerBuilder, targetPackage)
        generateMethods(classBuilder, companionBuilder, meta)
        generateFields(classBuilder, meta)

        classBuilder.addType(companionBuilder.build())
        generateCloseMethod(classBuilder, meta)

        classBuilder.addInitializerBlock(initializerBuilder.build())
        val isThreadSafeRequired = meta.methods.any { it.methodType == XrossMethodType.MutInstance }
        val fileSpec = FileSpec.builder(targetPackage, className)
            .indent("    ")
            // 明示的なインポートリストを整理
            .addImport(
                "java.lang.foreign",
                "FunctionDescriptor",
                "MemorySegment",
                "Linker",
                "SymbolLookup",
            )
            .apply {
                if (isThreadSafeRequired) {
                    addImport("kotlin.concurrent", "withLock")
                }
            }
            .addType(classBuilder.build())
        val fileContext = fileSpec
            .build().toString()
            .replace("public class", "class")
            .replace("public fun", "fun")
            .replace("public val", "val")
            .replace("public var", "var")
            .replace("public constructor", "constructor")
            .replace("public companion object", "companion object")
        val fileDir = outputDir.resolve(
            targetPackage.replace('.', '/')
        )
        fileDir.mkdirs()
        fileDir.resolve("$className.kt").writeText(fileContext)
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

    private fun generateCleaner(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        initializerBuilder: CodeBlock.Builder,
        targetPackage: String // パッケージ名を受け取る
    ) {
        val stateClassName = ClassName(targetPackage, "Deallocator")

        companionBuilder.addProperty(
            PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"))
                .addModifiers(KModifier.PRIVATE)
                .initializer("Cleaner.create()") // 直接文字列で指定するか型を明示
                .build()
        )

        val stateClass = TypeSpec.classBuilder(stateClassName)
            .addModifiers(KModifier.PRIVATE)
            .addSuperinterface(Runnable::class)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("segment", MemorySegment::class)
                    .addParameter("dropHandle", HANDLE_TYPE)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE).initializer("segment").build()
            )
            .addProperty(
                PropertySpec.builder("dropHandle", HANDLE_TYPE, KModifier.PRIVATE).initializer("dropHandle").build()
            )
            .addFunction(
                FunSpec.builder("run")
                    .addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("if (segment != MemorySegment.NULL)")
                    .beginControlFlow("try")
                    .addStatement("dropHandle.invokeExact(segment)")
                    .nextControlFlow("catch (e: Throwable)")
                    .addStatement("System.err.println(%S + e.message)", "Xross: Failed to drop native object: ")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
            )
            .build()
        classBuilder.addProperty(
            PropertySpec.builder("cleanable", ClassName("java.lang.ref.Cleaner", "Cleanable"), KModifier.PRIVATE)
                .build()
        )

        initializerBuilder
            .addStatement("this.cleanable = CLEANER.register(this, Deallocator(this.segment, dropHandle))")
        classBuilder.addType(stateClass)
    }

    private fun generateClone(
        classBuilder: TypeSpec.Builder,
        meta: XrossClass
    ) {
        val className = meta.structName
        // クラス内に &mut self メソッドが存在するか判定
        val hasMutMethods = meta.methods.any { it.methodType == XrossMethodType.MutInstance }

        val cloneFun = FunSpec.builder("clone")
            .returns(ClassName("", className))
            .addKdoc("Creates a copy of this instance using the underlying native clone function.")
            .addStatement("val currentSegment = segment")
            .beginControlFlow("if (currentSegment == MemorySegment.NULL)")
            .addStatement("throw NullPointerException(%S)", "Cannot clone a dropped object")
            .endControlFlow()
            .beginControlFlow("try")

        val body = CodeBlock.builder()
        if (hasMutMethods) {
            body.beginControlFlow("return lock.readLock().withLock") // .readLock() を追加
            body.addStatement("val newRaw = cloneHandle.invokeExact(currentSegment) as MemorySegment")
            body.addStatement("$className(newRaw)")
            body.endControlFlow()
        } else {
            body.addStatement("val newRaw = cloneHandle.invokeExact(currentSegment) as MemorySegment")
            body.addStatement("return $className(newRaw)")
        }

        cloneFun.addCode(body.build())
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw %T(%S, e)", RuntimeException::class, "Failed to clone $className")
            .endControlFlow()

        classBuilder.addFunction(cloneFun.build())
    }

    private fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val fields = meta.fields
        val hasMutMethods = meta.methods.any { it.methodType == XrossMethodType.MutInstance }

        fields.forEach { field ->
            val camelName = field.name.toCamelCase()

            val getterBuilder = FunSpec.getterBuilder()
            val setterBuilder = FunSpec.setterBuilder().addParameter("value", field.ty.kotlinType)

            if (hasMutMethods) {
                // getter は readLock
                getterBuilder.addStatement(
                    "return lock.readLock().withLock { segment.get(%M, OFFSET_${field.name}.offset) }",
                    field.ty.layoutMember
                )
                // setter は writeLock
                setterBuilder.addStatement(
                    "lock.writeLock().withLock { segment.set(%M, OFFSET_${field.name}.offset, value) }",
                    field.ty.layoutMember
                )
            } else {
                getterBuilder.addStatement("return segment.get(%M, OFFSET_${field.name}.offset)", field.ty.layoutMember)
                setterBuilder.addStatement("segment.set(%M, OFFSET_${field.name}.offset, value)", field.ty.layoutMember)
            }

            val prop = PropertySpec.builder(camelName, field.ty.kotlinType)
                .mutable(true)
                .getter(getterBuilder.build())
                .setter(setterBuilder.build())
                .build()

            classBuilder.addProperty(prop)
        }
    }

    private fun generateMethods(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossClass) {
        val methods = meta.methods.filter { !it.isConstructor }
        val hasMutMethods = methods.any { it.methodType == XrossMethodType.MutInstance }

        if (hasMutMethods) {
            classBuilder.addProperty(
                PropertySpec.builder("lock", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("ReentrantReadWriteLock()")
                    .build()
            )
        }

        methods.forEach { method ->
            val isStringRet = method.ret is XrossType.StringType
            val isOwned = method.methodType == XrossMethodType.OwnedInstance
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
            // ... (Arena確保・引数準備のロジックは変更なし) ...
            val needsArena = method.args.any { it.ty is XrossType.StringType || it.ty is XrossType.Slice }
            if (needsArena) body.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class)

            val invokeArgs = mutableListOf<String>()
            if (method.methodType != XrossMethodType.Static) invokeArgs.add("currentSegment")

            // (中略: 引数準備)
            method.args.forEach { arg ->
                val argCamel = arg.name.toCamelCase()
                when (val ty = arg.ty) {
                    is XrossType.StringType -> {
                        body.addStatement("val ${argCamel}Seg = arena.allocateFrom($argCamel)")
                        invokeArgs.add("${argCamel}Seg")
                    }

                    is XrossType.Slice -> {
                        body.addStatement(
                            "val ${argCamel}Data = arena.allocateArray(%M, $argCamel.size.toLong())",
                            ty.elementType.layoutMember
                        )
                        body.addStatement(
                            "%T.copy($argCamel, 0, ${argCamel}Data, 0, $argCamel.byteSize())",
                            MemorySegment::class
                        )
                        body.addStatement("val ${argCamel}Slice = arena.allocate(16, 8)")
                        body.addStatement("${argCamel}Slice.set(%M, 0, ${argCamel}Data)", ADDRESS)
                        body.addStatement("${argCamel}Slice.set(%M, 8, $argCamel.size.toLong())", JAVA_LONG)
                        invokeArgs.add("${argCamel}Slice")
                    }

                    else -> invokeArgs.add(argCamel)
                }
            }

            val call = "${method.name}Handle.invokeExact(${invokeArgs.joinToString()})"

            // ロックの種類の判定
            // MutInstance / OwnedInstance -> writeLock
            // ConstInstance -> readLock
            val lockType = when {
                !hasMutMethods || method.methodType == XrossMethodType.Static -> null
                method.methodType == XrossMethodType.MutInstance || method.methodType == XrossMethodType.OwnedInstance -> "writeLock()"
                else -> "readLock()"
            }

            if (lockType != null) {
                if (method.ret is XrossType.Void) {
                    body.beginControlFlow("lock.%L.withLock", lockType)
                    body.addStatement("%L as Unit", call)
                    if (isOwned) body.addStatement("segment = MemorySegment.NULL")
                    body.endControlFlow()
                } else if (isStringRet) {
                    body.beginControlFlow("val res = lock.%L.withLock", lockType)
                    body.addStatement("%L as MemorySegment", call)
                    if (isOwned) body.addStatement("segment = MemorySegment.NULL")
                    body.endControlFlow()
                    body.addStatement("if (res == MemorySegment.NULL) return \"\"")
                    body.addStatement("val str = res.reinterpret(Long.MAX_VALUE).getString(0)")
                    body.addStatement("xross_free_stringHandle.invokeExact(res)")
                    body.addStatement("return str")
                } else {
                    body.beginControlFlow("return lock.%L.withLock", lockType)
                    body.addStatement("val result = %L as %T", call, returnType)
                    if (isOwned) body.addStatement("segment = MemorySegment.NULL")
                    body.addStatement("result")
                    body.endControlFlow()
                }
            } else {
                if (method.ret is XrossType.Void) {
                    body.addStatement("%L as Unit", call)
                } else if (isStringRet) {
                    body.addStatement("val res = %L as MemorySegment", call)
                    body.addStatement("if (res == MemorySegment.NULL) return \"\"")
                    body.addStatement("val str = res.reinterpret(Long.MAX_VALUE).getString(0)")
                    body.addStatement("xross_free_stringHandle.invokeExact(res)")
                    body.addStatement("return str")
                } else {
                    body.addStatement("return %L as %T", call, returnType)
                }
            }

            if (needsArena) body.endControlFlow()
            body.nextControlFlow("catch (e: Throwable)").addStatement("throw RuntimeException(e)").endControlFlow()

            funBuilder.addCode(body.build())
            if (method.methodType == XrossMethodType.Static) companionBuilder.addFunction(funBuilder.build())
            else classBuilder.addFunction(funBuilder.build())
        }
    }

    private fun generateCompanionBuilder(meta: XrossClass): TypeSpec.Builder {
        val builder = TypeSpec.companionObjectBuilder()

        // プロパティ定義
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
                PropertySpec.builder("OFFSET_${it.name}", ClassName("", "FieldMemoryInfo"), KModifier.PRIVATE).build()
            )
        }

        val init = CodeBlock.builder()
            .addStatement("val linker = %T.nativeLinker()", Linker::class)
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class)
            .apply {
                // 1. xross_free_string (固定)
                addStatement(
                    "xross_free_stringHandle = linker.downcallHandle(lookup.find(\"xross_free_string\").get(), %T.ofVoid(%M))",
                    FunctionDescriptor::class, ADDRESS
                )

                // 2. Core Handles (new, drop, clone, layout)
                CORE_HANDLES.forEach { suffix ->
                    val desc = when (suffix) {
                        "drop" -> CodeBlock.of("%T.ofVoid(%M)", FunctionDescriptor::class, ADDRESS)
                        "new" -> {
                            val ctor = meta.methods.find { it.isConstructor }
                            val ctorArgs = ctor?.args?.map { CodeBlock.of("%M", it.ty.layoutMember) } ?: emptyList()
                            if (ctorArgs.isEmpty()) {
                                CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                            } else {
                                CodeBlock.of(
                                    "%T.of(%M, %L)",
                                    FunctionDescriptor::class,
                                    ADDRESS,
                                    ctorArgs.joinToCode(", ")
                                )
                            }
                        }

                        "clone" -> CodeBlock.of("%T.of(%M, %M)", FunctionDescriptor::class, ADDRESS, ADDRESS)
                        "layout" -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                        else -> CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, ADDRESS)
                    }
                    addStatement(
                        "${suffix}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        "${meta.symbolPrefix}_$suffix", desc
                    )
                }

                // 3. Custom Methods
                meta.methods.filter { !it.isConstructor }.forEach { method ->
                    val argLayouts = mutableListOf<CodeBlock>()
                    if (method.methodType != XrossMethodType.Static) {
                        argLayouts.add(CodeBlock.of("%M", ADDRESS))
                    }
                    method.args.forEach { arg ->
                        argLayouts.add(CodeBlock.of("%M", arg.ty.layoutMember))
                    }

                    val desc = if (method.ret is XrossType.Void) {
                        // ofVoid(arg1, arg2...)
                        if (argLayouts.isEmpty()) {
                            CodeBlock.of("%T.ofVoid()", FunctionDescriptor::class)
                        } else {
                            CodeBlock.of("%T.ofVoid(%L)", FunctionDescriptor::class, argLayouts.joinToCode(", "))
                        }
                    } else {
                        // of(ret, arg1, arg2...)
                        if (argLayouts.isEmpty()) {
                            CodeBlock.of("%T.of(%M)", FunctionDescriptor::class, method.ret.layoutMember)
                        } else {
                            CodeBlock.of(
                                "%T.of(%M, %L)",
                                FunctionDescriptor::class,
                                method.ret.layoutMember,
                                argLayouts.joinToCode(", ")
                            )
                        }
                    }

                    addStatement(
                        "${method.name}Handle = linker.downcallHandle(lookup.find(%S).get(), %L)",
                        method.symbol, desc
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
            .addStatement("STRUCT_SIZE = tempMap[\"__self\"]?.size ?: (tempMap.values.maxOfOrNull { it.offset + 8 } ?: 0L)")
            .nextControlFlow("catch (e: Throwable)")
            .addStatement("throw RuntimeException(\"Init failed for ${meta.structName}\", e)").endControlFlow()
        return builder.addInitializerBlock(init.build())
    }

    private fun generateCloseMethod(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val hasMutMethods = meta.methods.any { it.methodType == XrossMethodType.MutInstance }

        val body = CodeBlock.builder()
        body.beginControlFlow("if (segment != MemorySegment.NULL)")

        if (hasMutMethods) {
            // .writeLock() を追加
            body.beginControlFlow("lock.writeLock().withLock")
            body.addStatement("cleanable.clean()")
            body.addStatement("segment = MemorySegment.NULL")
            body.endControlFlow()
        } else {
            body.addStatement("cleanable.clean()")
            body.addStatement("segment = MemorySegment.NULL")
        }
        body.endControlFlow()

        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(body.build())
                .build()
        )
    }

    private fun buildFieldMemoryInfoType() = TypeSpec.classBuilder("FieldMemoryInfo")
        .addModifiers(KModifier.PRIVATE, KModifier.DATA).primaryConstructor(
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
