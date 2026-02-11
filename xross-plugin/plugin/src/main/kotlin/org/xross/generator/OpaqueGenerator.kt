package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import java.io.File
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

object OpaqueGenerator {

    fun generateSingle(meta: XrossDefinition.Opaque, outputDir: File, targetPackage: String, basePackage: String) {
        val className = meta.name
        val prefix = meta.symbolPrefix
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")

        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(AutoCloseable::class)
            .addKdoc(meta.docs.joinToString("\n"))

        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("raw", MemorySegment::class)
                .addParameter("autoArena", ClassName("java.lang.foreign", "Arena"))
                .addParameter(
                    ParameterSpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true))
                        .defaultValue("null").build()
                )
                .addParameter(
                    ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true))
                        .defaultValue("null").build()
                )
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("autoArena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL)
                .initializer("autoArena")
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true), KModifier.INTERNAL)
                .initializer("confinedArena")
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("aliveFlag", aliveFlagType, KModifier.INTERNAL)
                .initializer(CodeBlock.of("sharedFlag ?: %T(true)", aliveFlagType))
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL)
                .mutable()
                .initializer("raw")
                .build()
        )

        // --- clone メソッド ---
        if (meta.isClonable) {
            classBuilder.addFunction(
                FunSpec.builder("clone")
                    .returns(ClassName(targetPackage, className))
                    .beginControlFlow("try")
                    .addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Object dropped or invalid")
                    .addStatement("val newAutoArena = Arena.ofAuto()")
                    .addStatement("val newConfinedArena = Arena.ofConfined()")
                    .addStatement("val flag = %T(true)", aliveFlagType)
                    .addStatement("val raw = cloneHandle.invokeExact(this.segment) as MemorySegment")
                    .addStatement("val res = raw.reinterpret(STRUCT_SIZE, newAutoArena) { s -> if (flag.isValid) { flag.isValid = false; dropHandle.invokeExact(s); try { newConfinedArena.close() } catch (e: Throwable) {} } }")
                    .addStatement("return %L(res, newAutoArena, confinedArena = newConfinedArena, sharedFlag = flag)", className)
                    .nextControlFlow("catch (e: Throwable)")
                    .addStatement("throw RuntimeException(e)")
                    .endControlFlow()
                    .build()
            )
        }

        // --- close メソッド ---
        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != MemorySegment.NULL)")
                .addStatement("aliveFlag.isValid = false")
                .addStatement("segment = MemorySegment.NULL")
                .beginControlFlow("if (confinedArena != null)")
                .beginControlFlow("try")
                .addStatement("confinedArena.close()")
                .nextControlFlow("catch (e: UnsupportedOperationException)")
                .addStatement("// Ignore for non-closeable arenas")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .build()
        )

        // --- Companion Object ---
        val companion = TypeSpec.companionObjectBuilder()
            .addProperty(PropertySpec.builder("dropHandle", MethodHandle::class, KModifier.INTERNAL).build())
            .addProperty(PropertySpec.builder("STRUCT_SIZE", Long::class, KModifier.INTERNAL).initializer("0L").mutable().build())

        val fromPointerBuilder = FunSpec.builder("fromPointer")
            .addParameter("ptr", MemorySegment::class)
            .addParameter("autoArena", ClassName("java.lang.foreign", "Arena"))
            .addParameter(ParameterSpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true)).defaultValue("null").build())
            .addParameter(ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true)).defaultValue("null").build())
            .returns(ClassName(targetPackage, className))
            .addModifiers(KModifier.INTERNAL)
            .addCode("return %L(ptr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)\n", className)
        
        companion.addFunction(fromPointerBuilder.build())
            
        if (meta.isClonable) {
            companion.addProperty(PropertySpec.builder("cloneHandle", MethodHandle::class, KModifier.PRIVATE).build())
        }

        val initBlock = CodeBlock.builder()
            .addStatement("val linker = Linker.nativeLinker()")
            .addStatement("val lookup = SymbolLookup.loaderLookup()")
            .addStatement("this.dropHandle = linker.downcallHandle(lookup.find(\"${prefix}_drop\").get(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))")

        if (meta.isClonable) {
            initBlock.addStatement("this.cloneHandle = linker.downcallHandle(lookup.find(\"${prefix}_clone\").get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))")
        }

        initBlock.addStatement("val sizeHandle = linker.downcallHandle(lookup.find(\"${prefix}_size\").get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG))")
        initBlock.addStatement("this.STRUCT_SIZE = sizeHandle.invokeExact() as Long")

        companion.addInitializerBlock(initBlock.build())
        classBuilder.addType(companion.build())

        // --- ファイル書き出し ---
        val fileSpec = FileSpec.builder(targetPackage, className)
            .addImport("java.lang.foreign", "Linker", "SymbolLookup", "FunctionDescriptor", "ValueLayout", "Arena")
            .addType(classBuilder.build())
            .build()
        
        writeToDisk(fileSpec, targetPackage, className, outputDir)
    }

    private fun writeToDisk(fileSpec: FileSpec, pkg: String, name: String, outputDir: File) {
        val content = XrossGenerator.cleanupPublic(fileSpec.toString())
        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$name.kt").writeText(content)
    }
}
