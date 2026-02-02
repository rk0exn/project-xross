package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossClass
import org.xross.structures.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    fun buildBase(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        // FieldMemoryInfo: companion から参照するため INTERNAL
        classBuilder.addType(
            TypeSpec.classBuilder("FieldMemoryInfo")
                .addModifiers(KModifier.INTERNAL, KModifier.DATA)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("offset", Long::class)
                        .addParameter("size", Long::class).build()
                )
                .addProperty(PropertySpec.builder("offset", Long::class).initializer("offset").build())
                .addProperty(PropertySpec.builder("size", Long::class).initializer("size").build())
                .build()
        )

        // AliveFlag: 参照型と共有するため INTERNAL
        classBuilder.addType(
            TypeSpec.classBuilder("AliveFlag")
                .addModifiers(KModifier.INTERNAL)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("initial", Boolean::class).build())
                .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("initial").build())
                .build()
        )

        // プライマリコンストラクタ: PRIVATE
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("raw", MemorySegment::class)
                .addParameter(ParameterSpec.builder("isBorrowed", Boolean::class).defaultValue("false").build())
                .addParameter(
                    ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true))
                        .defaultValue("null").build()
                )
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.PRIVATE)
                .initializer("sharedFlag ?: AliveFlag(true)")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE)
                .mutable()
                .initializer("raw")
                .build()
        )

        if (meta.methods.any { it.methodType != XrossMethodType.Static } || meta.fields.isNotEmpty()) {
            classBuilder.addProperty(
                PropertySpec.builder("lock", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("ReentrantReadWriteLock()")
                    .build()
            )
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        val deallocatorName = "Deallocator"
        val handleType = ClassName("java.lang.invoke", "MethodHandle")
        classBuilder.addType(buildDeallocator(handleType))

        classBuilder.addProperty(
            PropertySpec.builder(
                "cleanable",
                ClassName("java.lang.ref.Cleaner", "Cleanable").copy(nullable = true),
                KModifier.PRIVATE
            )
                .mutable()
                .initializer(
                    "if (isBorrowed) null else CLEANER.register(this, %L(segment, dropHandle))",
                    deallocatorName
                )
                .build()
        )

        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
            .addStatement("aliveFlag.isValid = false")
            .apply {
                val hasLock = meta.methods.any { it.methodType != XrossMethodType.Static } || meta.fields.isNotEmpty()
                if (hasLock) beginControlFlow("lock.writeLock().withLock")
                addStatement("cleanable?.clean()")
                addStatement("segment = %T.NULL", MemorySegment::class)
                if (hasLock) endControlFlow()
            }
            .endControlFlow()

        classBuilder.addFunction(
            FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addCode(closeBody.build()).build()
        )
    }

    private fun buildDeallocator(handleType: TypeName) = TypeSpec.classBuilder("Deallocator")
        .addModifiers(KModifier.PRIVATE)
        .addSuperinterface(Runnable::class)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("segment", MemorySegment::class)
                .addParameter("dropHandle", handleType).build()
        )
        .addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PRIVATE).initializer("segment").build()
        )
        .addProperty(
            PropertySpec.builder("dropHandle", handleType, KModifier.PRIVATE).initializer("dropHandle").build()
        )
        .addFunction(
            FunSpec.builder("run")
                .addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
                .beginControlFlow("try")
                .addStatement("dropHandle.invokeExact(segment)")
                .nextControlFlow("catch (e: Throwable)")
                // 修正箇所: %S がリテラル文字列として展開されるようにします
                .addStatement("System.err.println(%S + e.message)", "Xross: Failed to drop native object: ")
                .endControlFlow()
                .endControlFlow()
                .build()
        )
        .build()
}
