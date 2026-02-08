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
                .addModifiers(KModifier.INTERNAL)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("initial", Boolean::class).build())
                .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable().initializer("initial").build())
                .build()
        )

        // --- プライマリコンストラクタ ---
        val constructorBuilder = FunSpec.constructorBuilder()
            .addModifiers(if (meta is XrossDefinition.Enum) KModifier.PROTECTED else KModifier.INTERNAL)
            .addParameter("raw", MemorySegment::class)
            .addParameter(
                ParameterSpec.builder("arena", ClassName("java.lang.foreign", "Arena"))
                    .build()
            )
            .addParameter(
                ParameterSpec.builder("isArenaOwner", Boolean::class)
                    .defaultValue("true").build()
            )
            .addParameter(
                ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true))
                    .defaultValue("null").build()
            )
        classBuilder.primaryConstructor(constructorBuilder.build())

        // --- プロパティの定義 ---
        classBuilder.addProperty(
            PropertySpec.builder("arena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL)
                .initializer("arena")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("isArenaOwner", Boolean::class, KModifier.INTERNAL)
                .initializer("isArenaOwner")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("aliveFlag", ClassName("", "AliveFlag"), KModifier.INTERNAL)
                .initializer("sharedFlag ?: AliveFlag(true)")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("segment", MemorySegment::class, KModifier.INTERNAL)
                .mutable()
                .initializer("raw")
                .build()
        )

        classBuilder.addProperty(
            PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                .addModifiers(KModifier.INTERNAL)
                .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                .build()
        )

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
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    beginControlFlow("if (isArenaOwner)")
                    beginControlFlow("try")
                    addStatement("arena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class)
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
                    nextControlFlow("finally")
                    addStatement("sl.unlockWrite(stamp)")
                    endControlFlow()
                } else {
                    addStatement("segment = %T.NULL", MemorySegment::class)
                    beginControlFlow("if (isArenaOwner)")
                    beginControlFlow("try")
                    addStatement("arena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class)
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
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
}
