package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import java.io.File
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.ref.Cleaner

object OpaqueGenerator {

    fun generateSingle(meta: XrossDefinition.Opaque, outputDir: File, targetPackage: String) {
        val className = meta.name
        val prefix = meta.symbolPrefix

        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(AutoCloseable::class)
            .addKdoc(meta.docs.joinToString("\n"))

        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("raw", MemorySegment::class)
                .addParameter(
                    ParameterSpec.builder("parent", ANY.copy(nullable = true))
                        .defaultValue("null")
                        .build()
                )
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("parent",ANY.copy(nullable = true), KModifier.PRIVATE)
                .initializer("parent")
                .build()
        )
        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL)
                .mutable()
                .initializer("raw")
                .build()
        )
        // --- Cleaner によるメモリ管理 (Type mismatch 解決) ---
        // copy(nullable = true) を使用して Cleaner.Cleanable? 型にする
        val cleanableType = Cleaner.Cleanable::class.asClassName().copy(nullable = true)
        classBuilder.addProperty(
            PropertySpec.builder("cleanable", cleanableType, KModifier.PRIVATE)
                .initializer("if (parent !=null || raw == MemorySegment.NULL) null else CLEANER.register(this, Deallocator(raw, dropHandle))")
                .build()
        )

        // --- clone メソッド ---
        if (meta.isClonable) {
            classBuilder.addFunction(
                FunSpec.builder("clone")
                    .returns(ClassName(targetPackage, className))
                    .beginControlFlow("try")
                    .addStatement("val newPtr = cloneHandle.invokeExact(segment) as MemorySegment")
                    .addStatement("return %L(newPtr, false)", className)
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
                .beginControlFlow("if (segment != MemorySegment.NULL && parent == null)")
                .addStatement("cleanable?.clean()")
                .addStatement("segment = MemorySegment.NULL")
                .endControlFlow()
                .build()
        )

        // --- Companion Object ---
        val companion = TypeSpec.companionObjectBuilder()
            .addProperty(PropertySpec.builder("dropHandle", MethodHandle::class, KModifier.PRIVATE).build())
            .addProperty(PropertySpec.builder("LAYOUT_SIZE", Long::class).initializer("0L").mutable().build())
            .addProperty(
                PropertySpec.builder("CLEANER", Cleaner::class, KModifier.PRIVATE).initializer("Cleaner.create()")
                    .build()
            )
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
        initBlock.addStatement("this.LAYOUT_SIZE = sizeHandle.invokeExact() as Long")

        companion.addInitializerBlock(initBlock.build())
        classBuilder.addType(companion.build())

        // --- Deallocator クラス ---
        val deallocator = TypeSpec.classBuilder("Deallocator")
            .addModifiers(KModifier.PRIVATE)
            .addSuperinterface(Runnable::class)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("segment", MemorySegment::class)
                    .addParameter("dropHandle", MethodHandle::class)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE).initializer("segment").build()
            )
            .addProperty(
                PropertySpec.builder("dropHandle", MethodHandle::class, KModifier.PRIVATE).initializer("dropHandle")
                    .build()
            )
            .addFunction(
                FunSpec.builder("run")
                    .addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("if (segment != MemorySegment.NULL)")
                    .beginControlFlow("try")
                    .addStatement("dropHandle.invokeExact(segment)")
                    .nextControlFlow("catch (e: Throwable)")
                    .addStatement("System.err.println(%S + e.message)", "Xross: Failed to drop native opaque object: ")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
            )
            .build()

        classBuilder.addType(deallocator)

        // --- ファイル書き出し (不足していたインポートの追加) ---
        val fileSpecBuilder = FileSpec.builder(targetPackage, className)
            .addImport("java.lang.foreign", "Linker", "SymbolLookup", "FunctionDescriptor", "ValueLayout")
            .addType(classBuilder.build())
        if (meta.isClonable) {
            fileSpecBuilder
                .addImport("java.lang", "RuntimeException")
        }
        val fileSpec = fileSpecBuilder.build()
        writeToDisk(fileSpec, targetPackage, className, outputDir)
    }

    private fun writeToDisk(fileSpec: FileSpec, pkg: String, name: String, outputDir: File) {
        var content = fileSpec.toString()
        // 不要な public 等を削る置換
        listOf("class", "fun", "val", "var", "constructor", "open", "companion", "init", "private").forEach {
            content = content.replace("public $it", it)
        }
        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$name.kt").writeText(content)
    }
}
