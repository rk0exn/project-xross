package org.xross

import com.squareup.kotlinpoet.*
import java.io.File

object XrossGenerator {
    private val memorySegment = ClassName("java.lang.foreign", "MemorySegment")
    private val functionDescriptor = ClassName("java.lang.foreign", "FunctionDescriptor")
    private val methodHandle = ClassName("java.lang.invoke", "MethodHandle")
    private val symbolLookup = ClassName("java.lang.foreign", "SymbolLookup")

    fun generate(meta: XrossClass, outputDir: File, targetPackage: String) {
        val fileSpec = FileSpec.builder(targetPackage, meta.structName)
            .addImport("java.lang.foreign", "ValueLayout", "Linker", "Arena")

        val structClass = TypeSpec.classBuilder(meta.structName)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("segment", memorySegment)
                .addModifiers(KModifier.INTERNAL).build())
            .addProperty(PropertySpec.builder("segment", memorySegment, KModifier.PRIVATE).initializer("segment").build())
            .apply {
                // インスタンスメソッドの追加
                meta.methods.filter { it.methodType != XrossMethodType.Static && !it.isConstructor }.forEach {
                    addFunction(generateMethod(it, false, meta.structName, targetPackage))
                }
            }
            .addType(generateCompanion(meta)) // Companion Object 内に MH 等を集約
            .build()

        fileSpec.addType(structClass).build().writeTo(outputDir)
    }

    private fun generateCompanion(meta: XrossClass): TypeSpec {
        return TypeSpec.companionObjectBuilder()
            .apply {
                // MethodHandles の定義
                meta.methods.forEach {
                    addProperty(PropertySpec.builder("h_${it.name}", methodHandle, KModifier.PRIVATE, KModifier.LATEINIT).mutable().build())
                }

                // Static/Constructor メソッドの追加
                meta.methods.filter { it.methodType == XrossMethodType.Static || it.isConstructor }.forEach {
                    addFunction(generateMethod(it, true, meta.structName, meta.packageName))
                }
            }
            .addFunction(FunSpec.builder("init")
                .addParameter("lookup", symbolLookup)
                .addCode(CodeBlock.builder().apply {
                    addStatement("val linker = java.lang.foreign.Linker.nativeLinker()")
                    meta.methods.forEach { method ->
                        add("h_${method.name} = linker.downcallHandle(\n")
                        indent()
                        add("lookup.find(%S).get(),\n", method.symbol)
                        add("%T.of(\n", functionDescriptor)
                        indent()
                        if (method.ret != XrossType.Void) add("%M,\n", method.ret.layoutMember)
                        if (method.methodType != XrossMethodType.Static && !method.isConstructor) {
                            add("java.lang.foreign.ValueLayout.ADDRESS,\n")
                        }
                        method.args.forEach { add("%M,\n", it.ty.layoutMember) }
                        unindent()
                        add(")\n")
                        unindent()
                        add(")\n")
                    }
                }.build())
                .build())
            .build()
    }

    private fun generateMethod(method: XrossMethod, isStatic: Boolean, structName: String, pkg: String): FunSpec {
        return FunSpec.builder(method.name)
            .addKdoc(method.docs.joinToString("\n"))
            .apply {
                if (method.isConstructor) addParameter("arena", ClassName("java.lang.foreign", "Arena"))
                method.args.forEach { field -> addParameter(field.name, field.ty.kotlinType) }
            }
            .apply {
                val callArgs = (if (!isStatic) listOf("this.segment") else emptyList()) +
                        method.args.map { it.name }
                val call = "h_${method.name}.invokeExact(${callArgs.joinToString(", ")})"

                if (method.isConstructor) {
                    returns(ClassName(pkg, structName))
                    addStatement("return $structName($call as %T)", memorySegment)
                } else if (method.ret != XrossType.Void) {
                    returns(method.ret.kotlinType)
                    addStatement("return $call as %T", method.ret.kotlinType)
                } else {
                    addStatement(call)
                }
            }
            .build()
    }
}
