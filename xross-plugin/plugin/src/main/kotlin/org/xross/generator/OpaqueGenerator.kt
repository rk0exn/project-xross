package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossType
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

object OpaqueGenerator {

    fun generateSingle(meta: XrossDefinition.Opaque, outputDir: File, targetPackage: String, basePackage: String) {
        val className = meta.name
        val classBuilder = TypeSpec.classBuilder(className)
            .addSuperinterface(AutoCloseable::class)
            .addKdoc(meta.docs.joinToString("\n"))

        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)
        CompanionGenerator.generateCompanions(companionBuilder, meta)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)

        // Add fields for Opaque
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val kType = if (field.ty is XrossType.Object) {
                GeneratorUtils.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            val propBuilder = PropertySpec.builder(escapedName, kType)
                .mutable(true) // External fields are assumed mutable
                .getter(buildOpaqueGetter(field, kType, basePackage))
                .setter(buildOpaqueSetter(field, kType, basePackage))
            classBuilder.addProperty(propBuilder.build())
        }

        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        val fileSpec = FileSpec.builder(targetPackage, className)
            .addType(classBuilder.build())
            .indent("    ")
            .build()

        GeneratorUtils.writeToDisk(fileSpec, outputDir)
    }

    private fun buildOpaqueGetter(field: XrossField, kType: TypeName, basePackage: String): FunSpec {
        val baseName = field.name.toCamelCase()
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val getHandle = if (field.ty is XrossType.RustString) "${baseName}StrGetHandle"
                        else if (field.ty is XrossType.Optional) "${baseName}OptGetHandle"
                        else if (field.ty is XrossType.Result) "${baseName}ResGetHandle"
                        else "${baseName}GetHandle"

        if (field.ty is XrossType.Result) {
             // Handle Result similarly to PropertyGenerator but using FFI handle
             body.addStatement("val resRaw = Companion.$getHandle.invokeExact(this.segment) as %T", MemorySegment::class)
             body.addStatement("val isOk = resRaw.get(%M, 0L) != (0).toByte()", MemberName("java.lang.foreign.ValueLayout", "JAVA_BYTE"))
             body.addStatement("val ptr = resRaw.get(%M, 8L)", MemberName("java.lang.foreign.ValueLayout", "ADDRESS"))
             body.beginControlFlow("val res = if (isOk)")
             // Simplified for Opaque
             body.addStatement("Result.success(ptr) // TODO: Full resolution if needed")
             body.nextControlFlow("else")
             body.addStatement("Result.failure(%T(ptr))", ClassName("$basePackage.xross.runtime", "XrossException"))
             body.endControlFlow()
             body.addStatement("return res as %T", kType)
        } else if (field.ty is XrossType.RustString) {
            GeneratorUtils.addRustStringResolution(body, "Companion.$getHandle.invokeExact(this.segment)", "s")
            body.addStatement("return s")
        } else {
            body.addStatement("return Companion.$getHandle.invokeExact(this.segment) as %T", kType)
        }

        return FunSpec.getterBuilder().addCode(body.build()).build()
    }

    private fun buildOpaqueSetter(field: XrossField, kType: TypeName, basePackage: String): FunSpec {
        val baseName = field.name.toCamelCase()
        val body = CodeBlock.builder()
        val setHandle = if (field.ty is XrossType.RustString) "${baseName}StrSetHandle"
                        else if (field.ty is XrossType.Optional) "${baseName}OptSetHandle"
                        else "${baseName}SetHandle"

        if (field.ty is XrossType.RustString) {
            body.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
            body.addStatement("val allocated = arena.allocateFrom(v)")
            body.addStatement("Companion.$setHandle.invokeExact(this.segment, allocated) as Unit")
            body.endControlFlow()
        } else {
            body.addStatement("Companion.$setHandle.invokeExact(this.segment, v) as Unit")
        }

        return FunSpec.setterBuilder().addParameter("v", kType).addCode(body.build()).build()
    }
}
