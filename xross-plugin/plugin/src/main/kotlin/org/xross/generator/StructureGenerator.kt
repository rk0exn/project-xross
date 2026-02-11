package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethodType
import java.lang.foreign.MemorySegment

object StructureGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    private val VH_TYPE = java.lang.invoke.VarHandle::class.asClassName()

    fun buildBase(classBuilder: TypeSpec.Builder, companionBuilder: TypeSpec.Builder, meta: XrossDefinition, basePackage: String) {
        val isEnum = meta is XrossDefinition.Enum
        val isPure = GeneratorUtils.isPureEnum(meta)
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)

        if (isPure) {
            // --- Pure Enum Case (enum class) ---
            val segmentProp = PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.INTERNAL)
                .mutable(true)
                .initializer("%T.NULL", MEMORY_SEGMENT)
                .build()
            classBuilder.addProperty(segmentProp)
            
            classBuilder.addProperty(PropertySpec.builder("aliveFlag", aliveFlagType, KModifier.INTERNAL)
                .initializer("%T(true)", aliveFlagType).build())

            classBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"))
                .addModifiers(KModifier.INTERNAL)
                .initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock"))
                .build())

            classBuilder.addProperty(PropertySpec.builder("autoArena", ClassName("java.lang.foreign", "Arena"), KModifier.INTERNAL)
                .initializer("%T.ofAuto()", ClassName("java.lang.foreign", "Arena"))
                .build())
            classBuilder.addProperty(PropertySpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true), KModifier.INTERNAL)
                .initializer("null")
                .build())

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
        }

        // --- resolveFieldSegment Helper ---
        val resolver = FunSpec.builder("resolveFieldSegment")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("parent", MEMORY_SEGMENT)
            .addParameter("vh", VH_TYPE.copy(nullable = true))
            .addParameter("offset", Long::class)
            .addParameter("size", Long::class)
            .addParameter("isOwned", Boolean::class)
            .returns(MEMORY_SEGMENT)
            .addCode("""
                if (parent == %T.NULL) return %T.NULL
                return if (isOwned) {
                    parent.asSlice(offset, size)
                } else {
                    if (vh == null) return %T.NULL
                    val ptr = vh.get(parent, offset) as %T
                    if (ptr == %T.NULL) %T.NULL else ptr.reinterpret(size)
                }
            """.trimIndent(), MEMORY_SEGMENT, MEMORY_SEGMENT, MEMORY_SEGMENT, MEMORY_SEGMENT, MEMORY_SEGMENT, MEMORY_SEGMENT)
            .build()
        classBuilder.addFunction(resolver)

        // --- fromPointer メソッド ---
        if (!isEnum) {
            val fromPointerBuilder = FunSpec.builder("fromPointer")
                .addParameter("ptr", MEMORY_SEGMENT)
                .addParameter("autoArena", ClassName("java.lang.foreign", "Arena"))
                .addParameter(ParameterSpec.builder("confinedArena", ClassName("java.lang.foreign", "Arena").copy(nullable = true)).defaultValue("null").build())
                .addParameter(ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true)).defaultValue("null").build())
                .returns(selfType)
                .addModifiers(KModifier.INTERNAL)
                .addCode("return %T(ptr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)\n", selfType)
            
            companionBuilder.addFunction(fromPointerBuilder.build())
        }
    }

    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        if (GeneratorUtils.isPureEnum(meta)) return

        val closeBody = CodeBlock.builder()
            .beginControlFlow("if (segment != %T.NULL)", MEMORY_SEGMENT)
            .addStatement("aliveFlag.isValid = false")
            .apply {
                val hasLock = meta.methods.any { it.methodType != XrossMethodType.Static } || (meta is XrossDefinition.Struct && meta.fields.isNotEmpty())
                if (hasLock) {
                    addStatement("val stamp = sl.writeLock()")
                    beginControlFlow("try")
                    addStatement("segment = %T.NULL", MEMORY_SEGMENT)
                    beginControlFlow("if (confinedArena != null)")
                    beginControlFlow("try")
                    addStatement("confinedArena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class.asTypeName())
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
                    nextControlFlow("finally")
                    addStatement("sl.unlockWrite(stamp)")
                    endControlFlow()
                } else {
                    addStatement("segment = %T.NULL", MEMORY_SEGMENT)
                    beginControlFlow("if (confinedArena != null)")
                    beginControlFlow("try")
                    addStatement("confinedArena.close()")
                    nextControlFlow("catch (e: %T)", UnsupportedOperationException::class.asTypeName())
                    addStatement("// Ignore for non-closeable arenas")
                    endControlFlow()
                    endControlFlow()
                }
            }
            .endControlFlow()

        classBuilder.addFunction(FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addCode(closeBody.build()).build())
    }
}