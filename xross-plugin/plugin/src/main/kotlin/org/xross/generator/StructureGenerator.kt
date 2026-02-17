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
        val xrossObject = ClassName("$basePackage.xross.runtime", "XrossObject")
        val xrossNativeObject = ClassName("$basePackage.xross.runtime", "XrossNativeObject")
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)

        // Determine if locks are needed
        val needsLocks = GeneratorUtils.needsLocks(meta)

        // --- Class Level (Static) ---
        val runtimePkg = "$basePackage.xross.runtime"
        if (needsLocks) {
            companionBuilder.addProperty(
                PropertySpec.builder("lockState", ClassName(runtimePkg, "XrossLockState"), KModifier.INTERNAL)
                    .delegate("lazy(LazyThreadSafetyMode.PUBLICATION) { %T() }", ClassName(runtimePkg, "XrossLockState"))
                    .build(),
            )
            companionBuilder.addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.sl").build()).build())
            companionBuilder.addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.fl").build()).build())
            companionBuilder.addProperty(PropertySpec.builder("al", ClassName(runtimePkg, "XrossAsyncLock")).addModifiers(KModifier.INTERNAL).getter(FunSpec.getterBuilder().addStatement("return lockState.al").build()).build())
        }

        if (meta is XrossDefinition.Function) return

        val isEnum = meta is XrossDefinition.Enum

        // 全てのクラス（Struct, Enum, Opaque）が XrossNativeObject を継承する
        classBuilder.superclass(xrossNativeObject)

        val constructorBuilder = FunSpec.constructorBuilder()
            .addModifiers(if (isEnum) KModifier.PROTECTED else KModifier.INTERNAL)
            .addParameter("raw", MEMORY_SEGMENT)
            .addParameter("parent", xrossObject.copy(nullable = true))
            .addParameter("isPersistent", BOOLEAN)

        classBuilder.primaryConstructor(constructorBuilder.build())
        classBuilder.addSuperclassConstructorParameter("raw")
        classBuilder.addSuperclassConstructorParameter("parent")
        classBuilder.addSuperclassConstructorParameter("isPersistent")

        if (needsLocks) {
            GeneratorUtils.addLockProperties(classBuilder, basePackage)
        }

        if (!isEnum) {
            val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", selfType, basePackage)
                .addCode("return %T(ptr, parent = parent, isPersistent = isPersistent)\n", selfType)
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
