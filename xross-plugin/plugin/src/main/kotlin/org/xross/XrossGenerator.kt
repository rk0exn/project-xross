package org.xross

import com.squareup.kotlinpoet.*
import java.io.File

object XrossGenerator {
    fun generate(meta: XrossClass, outputDir: File, crateName: String, targetPackage: String) {
        val libClassName = "Lib${crateName.replaceFirstChar { it.uppercase() }}"
        val memorySegment = ClassName("java.lang.foreign", "MemorySegment")
        val functionDescriptor = ClassName("java.lang.foreign", "FunctionDescriptor")
        val symbolLookup = ClassName("java.lang.foreign", "SymbolLookup")

        val fileSpec = FileSpec.builder(targetPackage, meta.structName)
            .addImport("java.lang.foreign", "ValueLayout", "Linker", "Arena")
            .addImport("java.lang.invoke", "MethodHandle")

        // --- Lib Object (Internal) ---
        val libObject = TypeSpec.objectBuilder(libClassName)
            .addModifiers(KModifier.INTERNAL)
            .addProperty(PropertySpec.builder("linker", ClassName("java.lang.foreign", "Linker"), KModifier.PRIVATE)
                .initializer("Linker.nativeLinker()").build())
            .addProperty(PropertySpec.builder("lookup", symbolLookup, KModifier.PRIVATE, KModifier.LATEINIT).mutable().build())
            .apply {
                meta.methods.forEach {
                    addProperty(PropertySpec.builder("h_${it.name}", ClassName("java.lang.invoke", "MethodHandle"), KModifier.INTERNAL, KModifier.LATEINIT).mutable().build())
                }
            }
            .addFunction(FunSpec.builder("init")
                .addCode(generateInitBody(crateName, meta, functionDescriptor))
                .build())
            .build()

        // --- Struct Class ---
        val structClass = TypeSpec.classBuilder(meta.structName)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("segment", memorySegment)
                .addModifiers(KModifier.INTERNAL).build())
            .addProperty(PropertySpec.builder("segment", memorySegment, KModifier.PRIVATE).initializer("segment").build())
            .apply {
                meta.methods.filter { it.methodType != XrossMethodType.Static && !it.isConstructor }.forEach {
                    addFunction(generateMethod(it, libClassName, false, meta.structName, targetPackage))
                }
            }
            .addType(TypeSpec.companionObjectBuilder()
                .apply {
                    meta.methods.filter { it.methodType == XrossMethodType.Static || it.isConstructor }.forEach {
                        addFunction(generateMethod(it, libClassName, true, meta.structName, targetPackage))
                    }
                }.build())
            .build()

        fileSpec.addType(libObject).addType(structClass).build().writeTo(outputDir)
    }

    private fun generateInitBody(crateName: String, meta: XrossClass, functionDescriptor: ClassName): CodeBlock {
        return CodeBlock.builder()
            .addStatement("if (::lookup.isInitialized) return")
            .addStatement("val os = System.getProperty(\"os.name\").lowercase()")
            .addStatement("val ext = if (os.contains(\"win\")) \"dll\" else if (os.contains(\"mac\")) \"dylib\" else \"so\"")
            .addStatement($$"val name = \"lib$${crateName.replace("-", "_")}.$ext\"")
            // ネイティブライブラリのロード処理（既存維持）
            .addStatement($$"val stream = this::class.java.getResourceAsStream(\"/xross/natives/$name\") ?: throw RuntimeException(\"Missing $name\")")
            .addStatement($$"val file = java.io.File.createTempFile(\"xross_\", \"_$name\").apply { deleteOnExit() }")
            .addStatement("stream.use { s -> file.outputStream().use { s.copyTo(it) } }")
            .addStatement("System.load(file.absolutePath)")
            .addStatement("lookup = SymbolLookup.libraryLookup(file.toPath(), Arena.global())")
            .apply {
                meta.methods.forEach { method ->
                    add("\n// %L\n", method.name)
                    add("h_${method.name} = linker.downcallHandle(\n")
                    indent()
                    add("lookup.find(%S).get(),\n", method.symbol)
                    add("%T.of(\n", functionDescriptor)
                    indent()
                    // 戻り値
                    if (method.ret != XrossType.Void) add("%M,\n", mapXrossTypeToMember(method.ret))
                    // レシーバ (Static以外は第1引数にADDRESSが必要)
                    if (method.methodType != XrossMethodType.Static && !method.isConstructor) {
                        add("ValueLayout.ADDRESS,\n")
                    }
                    method.args.forEach { add("%M,\n", mapXrossTypeToMember(it.ty)) }
                    unindent()
                    add(")\n")
                    unindent()
                    add(")\n")
                }
            }
            .build()
    }

    private fun generateMethod(method: XrossMethod, libName: String, isStatic: Boolean, structName: String, pkg: String): FunSpec {
        val memorySegment = ClassName("java.lang.foreign", "MemorySegment")
        return FunSpec.builder(method.name)
            .addKdoc(method.docs.joinToString("\n"))
            .apply {
                if (method.isConstructor) addParameter("arena", ClassName("java.lang.foreign", "Arena"))
                method.args.forEach { field -> addParameter(field.name, mapXrossTypeToKotlin(field.ty)) }
            }
            .apply {
                val callArgs = (if (!isStatic) listOf("this.segment") else emptyList()) +
                        method.args.map { it.name }
                val call = "$libName.h_${method.name}.invokeExact(${callArgs.joinToString(", ")})"

                if (method.isConstructor) {
                    returns(ClassName(pkg, structName))
                    addStatement("return $structName($call as %T)", memorySegment)
                } else if (method.ret != XrossType.Void) {
                    returns(mapXrossTypeToKotlin(method.ret))
                    addStatement("return $call as %T", mapXrossTypeToKotlin(method.ret))
                } else {
                    addStatement(call)
                }
            }
            .build()
    }

    private fun mapXrossTypeToKotlin(type: XrossType): TypeName = when (type) {
        XrossType.I32 -> INT
        XrossType.I64 -> LONG
        XrossType.F32 -> FLOAT
        XrossType.F64 -> DOUBLE
        XrossType.Pointer, XrossType.StringType, is XrossType.Slice -> ClassName("java.lang.foreign", "MemorySegment")
        XrossType.Void -> UNIT
        XrossType.Bool -> BOOLEAN
        XrossType.I8 -> BYTE
        XrossType.I16 -> SHORT
        XrossType.U16 -> CHAR
    }

    private fun mapXrossTypeToMember(type: XrossType): MemberName = MemberName("java.lang.foreign.ValueLayout", when (type) {
        XrossType.I32 -> "JAVA_INT"
        XrossType.I64 -> "JAVA_LONG"
        XrossType.F32 -> "JAVA_FLOAT"
        XrossType.F64 -> "JAVA_DOUBLE"
        XrossType.Bool -> "JAVA_BOOLEAN"
        XrossType.I8 -> "JAVA_BYTE"
        XrossType.I16 -> "JAVA_SHORT"
        XrossType.U16 -> "JAVA_CHAR"
        XrossType.Pointer, XrossType.StringType, is XrossType.Slice, XrossType.Void -> "ADDRESS"
    })
}
