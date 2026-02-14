package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.GeneratorUtils
import org.xross.structures.XrossDefinition
import java.lang.foreign.MemorySegment

/**
 * Generates the base structure for Kotlin classes/enums, including constructors and memory management.
 */
object StructureGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    /**
     * Builds the base properties and constructors for the generated class.
     */
    fun buildBase(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossDefinition, basePackage: String) {
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)

        // --- Class Level (Static) Locks ---
        // Always add locks to the companion object so static methods can use them.
        companionBuilder.addProperty(
            PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                .addModifiers(KModifier.INTERNAL)
                .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                .build(),
        )
        companionBuilder.addProperty(
            PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock"))
                .addModifiers(KModifier.INTERNAL)
                .initializer("%T(true)", ClassName("java.util.concurrent.locks", "ReentrantLock"))
                .build(),
        )
        companionBuilder.addProperty(
            PropertySpec.builder("al", ClassName("$basePackage.xross.runtime", "XrossAsyncLock"))
                .addModifiers(KModifier.INTERNAL)
                .initializer("%T()", ClassName("$basePackage.xross.runtime", "XrossAsyncLock"))
                .build(),
        )
        companionBuilder.addProperty(
            PropertySpec.builder("autoArena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL)
                .initializer("%T.ofAuto()", ClassName("java.lang.foreign", "Arena"))
                .build(),
        )

        if (meta is XrossDefinition.Function) return

        val isEnum = meta is XrossDefinition.Enum
        val isPure = GeneratorUtils.isPureEnum(meta)

        if (isPure) {
            // --- Pure Enum Case (enum class) ---
            val segmentProp = PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.INTERNAL)
                .mutable(true)
                .initializer("%T.NULL", MEMORY_SEGMENT)
                .build()
            classBuilder.addProperty(segmentProp)

            classBuilder.addProperty(
                PropertySpec.builder("aliveFlag", aliveFlagType, KModifier.INTERNAL)
                    .initializer("%T(true)", aliveFlagType).build(),
            )

            classBuilder.addProperty(
                PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                    .build(),
            )
            classBuilder.addProperty(
                PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock"))
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T(true)", ClassName("java.util.concurrent.locks", "ReentrantLock"))
                    .build(),
            )
            classBuilder.addProperty(
                PropertySpec.builder("al", ClassName("$basePackage.xross.runtime", "XrossAsyncLock"))
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", ClassName("$basePackage.xross.runtime", "XrossAsyncLock"))
                    .build(),
            )

            classBuilder.addProperty(
                PropertySpec.builder("autoArena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL)
                    .initializer("%T.ofAuto()", ClassName("java.lang.foreign", "Arena"))
                    .build(),
            )
            classBuilder.addProperty(
                PropertySpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true), KModifier.INTERNAL)
                    .initializer("null")
                    .build(),
            )
        } else {
            // --- Normal Struct / Complex Enum Case ---
            val constructorBuilder = FunSpec.constructorBuilder()
                .addModifiers(if (isEnum) KModifier.PROTECTED else KModifier.INTERNAL)
                .addParameter("raw", MEMORY_SEGMENT)
                .addParameter(ParameterSpec.builder("autoArena", ClassName("java.lang.foreign", "Arena")).build())
                .addParameter(ParameterSpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true)).defaultValue("null").build())
                .addParameter(ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true)).defaultValue("null").build())
            classBuilder.primaryConstructor(constructorBuilder.build())

            classBuilder.addProperty(PropertySpec.builder("autoArena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL).initializer("autoArena").build())
            classBuilder.addProperty(PropertySpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true), KModifier.INTERNAL).initializer("confinedArena").build())
            classBuilder.addProperty(PropertySpec.builder("aliveFlag", aliveFlagType, KModifier.INTERNAL).initializer(CodeBlock.of("sharedFlag ?: %T(true)", aliveFlagType)).build())

            val segmentProp = PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.INTERNAL)
                .mutable(true)
                .initializer("raw")
            if (isEnum) segmentProp.addModifiers(KModifier.OPEN)
            classBuilder.addProperty(segmentProp.build())

            classBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock")).build())
            classBuilder.addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock")).addModifiers(KModifier.INTERNAL).initializer("%T(true)", ClassName("java.util.concurrent.locks", "ReentrantLock")).build())
            classBuilder.addProperty(PropertySpec.builder("al", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).build())
        }

        // --- fromPointer メソッド ---
        if (!isEnum) {
            val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", selfType, basePackage)
                .addCode("return %T(ptr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)\n", selfType)

            companionBuilder.addFunction(fromPointerBuilder.build())
        }
    }

    /**
     * Adds finalization logic, such as `close` and `relinquish` methods.
     */
    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        if (GeneratorUtils.isPureEnum(meta)) return

        val relinquishInternalBody = CodeBlock.builder()
            .addStatement("segment = %T.NULL", MEMORY_SEGMENT)
            .addStatement("aliveFlag.invalidate()")
            .build()
        classBuilder.addFunction(
            FunSpec.builder("relinquishInternal")
                .addModifiers(KModifier.INTERNAL)
                .addCode(relinquishInternalBody)
                .build(),
        )

        val closeBody = CodeBlock.builder()
            .addStatement("val s = segment")
            .beginControlFlow("if (s != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("val stamp = sl.writeLock()")
            .beginControlFlow("try")
            .beginControlFlow("if (segment != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("val currentS = segment")
            .beginControlFlow("if (aliveFlag.tryInvalidate())")
            .addStatement("relinquishInternal()")
            .beginControlFlow("if (confinedArena != null)")
            .addStatement("dropHandle.invokeExact(currentS)")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .nextControlFlow("finally")
            .addStatement("sl.unlockWrite(stamp)")
            .endControlFlow()
            .endControlFlow()

        classBuilder.addFunction(FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addCode(closeBody.build()).build())

        val relinquishBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("val stamp = sl.writeLock()")
            .beginControlFlow("try")
            .addStatement("relinquishInternal()")
            .nextControlFlow("finally")
            .addStatement("sl.unlockWrite(stamp)")
            .endControlFlow()
            .endControlFlow()

        classBuilder.addFunction(FunSpec.builder("relinquish").addModifiers(KModifier.INTERNAL).addCode(relinquishBody.build()).build())
    }
}
