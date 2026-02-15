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
        val xrossNativeObject = ClassName("$basePackage.xross.runtime", "XrossNativeObject")
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)

        // --- Class Level (Static) ---
        companionBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock")).build())
        companionBuilder.addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock")).addModifiers(KModifier.INTERNAL).initializer("%T(true)", ClassName("java.util.concurrent.locks", "ReentrantLock")).build())
        companionBuilder.addProperty(PropertySpec.builder("al", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).build())
        
        if (meta is XrossDefinition.Function) return

        val isEnum = meta is XrossDefinition.Enum
        
        // 全てのクラス（Struct, Enum, Opaque）が XrossNativeObject を継承する
        classBuilder.superclass(xrossNativeObject)

        val constructorBuilder = FunSpec.constructorBuilder()
            .addModifiers(if (isEnum) KModifier.PROTECTED else KModifier.INTERNAL)
            .addParameter("raw", MEMORY_SEGMENT)
            .addParameter("arena", ClassName("java.lang.foreign", "Arena"))
            .addParameter("sharedFlag", aliveFlagType)
        
        classBuilder.primaryConstructor(constructorBuilder.build())
        classBuilder.addSuperclassConstructorParameter("raw")
        classBuilder.addSuperclassConstructorParameter("arena")
        classBuilder.addSuperclassConstructorParameter("sharedFlag")

        classBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("java.util.concurrent.locks", "StampedLock")).build())
        classBuilder.addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock")).addModifiers(KModifier.INTERNAL).initializer("%T(true)", ClassName("java.util.concurrent.locks", "ReentrantLock")).build())
        classBuilder.addProperty(PropertySpec.builder("al", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).addModifiers(KModifier.INTERNAL).initializer("%T()", ClassName("$basePackage.xross.runtime", "XrossAsyncLock")).build())

        if (!isEnum) {
            val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", selfType, basePackage)
                .addCode("return %T(ptr, arena, sharedFlag = sharedFlag)\n", selfType)
            companionBuilder.addFunction(fromPointerBuilder.build())
        }
    }

    /**
     * Finalization logic is now mostly handled by XrossNativeObject.
     */
    fun addFinalBlocks(classBuilder: TypeSpec.Builder, meta: XrossDefinition) {
        if (meta !is XrossDefinition.Enum) {
            classBuilder.addFunction(
                FunSpec.builder("relinquishInternal")
                    .addModifiers(KModifier.INTERNAL)
                    .addStatement("// Handled by base class or overridden if needed")
                    .build(),
            )
        }
    }
}