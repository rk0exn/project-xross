package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    fun buildBase(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        // --- AliveFlag ---
        classBuilder.addType(
            TypeSpec.classBuilder("AliveFlag")
                .addModifiers(KModifier.PRIVATE)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("initial", Boolean::class).build())
                .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("initial").build())
                .build()
        )

        // --- プライマリコンストラクタ ---
        val constructorBuilder = FunSpec.constructorBuilder()
            .addModifiers(KModifier.PRIVATE)
            .addParameter("raw", MemorySegment::class)
            .addParameter(ParameterSpec.builder("parent", ANY.copy(nullable = true)).defaultValue("null").build())
            .addParameter(
                ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true))
                    .defaultValue("null").build()
            )
        classBuilder.primaryConstructor(constructorBuilder.build())
        // --- プロパティの定義 ---
        classBuilder.addProperty(
            PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.PRIVATE)
                .initializer("sharedFlag ?: AliveFlag(true)")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.PROTECTED)
                .mutable()
                .initializer("raw")
                .build()
        )

        // --- StampedLock (sl) の定義 ---
        if (meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())) {
            classBuilder.addProperty(
                PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .build()
            )
        }

        if (meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }) {
            val initBlock = CodeBlock.builder()
                .beginControlFlow("if (raw == %T.NULL)", MemorySegment::class)
                .beginControlFlow("val res = when (this.name)")
                .apply {
                    meta.variants.forEach { variant ->
                        addStatement(
                            "%S -> new_%LHandle.invokeExact() as %T",
                            variant.name,
                            variant.name,
                            MemorySegment::class
                        )
                    }
                    addStatement("else -> %T.NULL", MemorySegment::class)
                }
                .endControlFlow()
                .addStatement(
                    "segment = if (res == %T.NULL || STRUCT_SIZE <= 0L) res else res.reinterpret(STRUCT_SIZE)",
                    MemorySegment::class
                )
                .endControlFlow()
            classBuilder.addInitializerBlock(initBlock.build())
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        val deallocatorName = "Deallocator"
        val handleType = ClassName("java.lang.invoke", "MethodHandle")
        classBuilder.addType(buildDeallocator(handleType))

        // cleanable の登録
        classBuilder.addProperty(
            PropertySpec.builder(
                "cleanable",
                ClassName("java.lang.ref.Cleaner", "Cleanable").copy(nullable = true),
                KModifier.PRIVATE
            )
                .mutable()
                .initializer(
                    "if (parent != null || segment == %T.NULL) null else CLEANER.register(this, %L(segment, dropHandle))",
                    MemorySegment::class, deallocatorName
                )
                .build()
        )

        // close メソッド
        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MemorySegment::class)
            .addStatement("aliveFlag.isValid = false")
            .apply {
                // ロックを保持しているかチェック
                val hasLock =
                    meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())
                if (hasLock) {
                    addStatement("val stamp = sl.writeLock()")
                    beginControlFlow("try")
                    addStatement("cleanable?.clean()")
                    // 2重解放防止のため、clean後にNULLをセット
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    nextControlFlow("finally")
                    addStatement("sl.unlockWrite(stamp)")
                    endControlFlow()
                } else {
                    addStatement("cleanable?.clean()")
                    addStatement("segment = %T.NULL", MemorySegment::class)
                }
            }
            .endControlFlow()

        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(closeBody.build())
                .build()
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
                .addStatement("System.err.println(%S + e.message)", "Xross: Failed to drop native object: ")
                .endControlFlow()
                .endControlFlow()
                .build()
        )
        .build()
}
