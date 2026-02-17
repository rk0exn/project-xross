package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import org.xross.generator.util.addArgumentPreparation
import org.xross.generator.util.addRustStringResolution
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object OpaqueGenerator {

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Opaque, basePackage: String) {
        // Add fields for Opaque
        val backingFields = mutableListOf<String>()
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val kType = if (field.ty is XrossType.Object) {
                GeneratorUtils.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            val backingFieldName = GeneratorUtils.addBackingPropertyIfNeeded(classBuilder, field, baseName, kType)
            if (backingFieldName != null) backingFields.add(backingFieldName)

            val propBuilder = PropertySpec.builder(escapedName, kType)
                .mutable(true) // External fields are assumed mutable
                .getter(buildOpaqueGetter(field, kType, backingFieldName, basePackage))
                .setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildOpaqueSetterBody(field, backingFieldName, basePackage), useAsyncLock = field.safety != XrossThreadSafety.Direct && field.safety != XrossThreadSafety.Unsafe))
            classBuilder.addProperty(propBuilder.build())
        }

        if (backingFields.isNotEmpty()) {
            val clearCache = FunSpec.builder("clearCache")
                .addModifiers(KModifier.OVERRIDE)
                .apply {
                    backingFields.forEach { addStatement("this.$it = null") }
                }
                .build()
            classBuilder.addFunction(clearCache)
        }
    }

    private fun buildOpaqueGetter(field: XrossField, kType: TypeName, backingFieldName: String?, basePackage: String): FunSpec {
        val baseName = field.name.toCamelCase()
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        if (backingFieldName != null) {
            body.addStatement("val cached = this.$backingFieldName")
            if (field.ty is XrossType.Object) {
                body.beginControlFlow("if (cached != null && cached.isValid)")
            } else {
                body.beginControlFlow("if (cached != null)")
            }
            body.addStatement("return cached")
            body.nextControlFlow("else")
        }

        val getHandle = when (field.ty) {
            is XrossType.RustString -> "${baseName}StrGetHandle"
            is XrossType.Optional -> "${baseName}OptGetHandle"
            is XrossType.Result -> "${baseName}ResGetHandle"
            else -> "${baseName}GetHandle"
        }

        when (field.ty) {
            is XrossType.Result -> {
                body.addStatement("val resRaw = $getHandle.invokeExact(this.segment) as %T", MemorySegment::class)
                body.add("val res = ")
                body.addResultResolution(field.ty, "resRaw", ClassName("", "UNUSED"), basePackage)
                body.add("\n")
            }

            is XrossType.RustString -> {
                body.addRustStringResolution("$getHandle.invokeExact(java.lang.foreign.Arena.ofAuto() as java.lang.foreign.SegmentAllocator, this.segment)", "s", basePackage = basePackage)
                body.addStatement("val res = s")
            }

            else -> {
                body.addStatement("val res = $getHandle.invokeExact(this.segment) as %T", kType)
            }
        }

        if (backingFieldName != null) {
            body.addStatement("this.$backingFieldName = res")
            body.addStatement("return res")
            body.endControlFlow()
        } else {
            body.addStatement("return res")
        }

        return FunSpec.getterBuilder().addCode(body.build()).build()
    }

    private fun buildOpaqueSetterBody(field: XrossField, backingFieldName: String?, basePackage: String): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("if (this.segment == %T.NULL || !this.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Object invalid")

        val setHandle = when (field.ty) {
            is XrossType.RustString -> "${field.name.toCamelCase()}StrSetHandle"
            is XrossType.Optional -> "${field.name.toCamelCase()}OptSetHandle"
            else -> "${field.name.toCamelCase()}SetHandle"
        }

        body.beginControlFlow("%T.ofConfined().use { arena ->", java.lang.foreign.Arena::class)
        val callArgs = mutableListOf<CodeBlock>()
        body.addArgumentPreparation(field.ty, "v", callArgs, basePackage = basePackage)
        body.addStatement("$setHandle.invoke(this.segment, ${callArgs.joinToString(", ")})")
        body.endControlFlow()

        if (backingFieldName != null) {
            body.addStatement("this.$backingFieldName = v")
        }

        return body.build()
    }
}
