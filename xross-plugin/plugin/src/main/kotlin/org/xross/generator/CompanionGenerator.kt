package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.FFMConstants
import org.xross.generator.util.GeneratorUtils
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

object CompanionGenerator {
    private val HANDLE_TYPE = MethodHandle::class.asClassName()
    private val VH_TYPE = VarHandle::class.asClassName()
    private val LAYOUT_TYPE = StructLayout::class.asClassName()
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    private val MEMORY_LAYOUT = MemoryLayout::class.asTypeName()
    private val JVM_FIELD = ClassName("kotlin.jvm", "JvmField")

    fun generateCompanions(companionBuilder: TypeSpec.Builder, meta: XrossDefinition, basePackage: String) {
        defineProperties(companionBuilder, meta, basePackage)

        val init = CodeBlock.builder()
            .addStatement("val lookup = %T.loaderLookup()", SymbolLookup::class.asTypeName())
            .addStatement("%T.initializeHeap(lookup, linker)", ClassName(if (basePackage.isEmpty()) "xross.runtime" else "$basePackage.xross.runtime", "XrossRuntime"))

        HandleResolver.resolveAllHandles(init, meta)

        if (meta !is XrossDefinition.Function) {
            init.add("\n// --- Native Layout Resolution ---\n")
            init.addStatement("var layoutRaw: %T = %T.NULL", MEMORY_SEGMENT, MEMORY_SEGMENT)
            init.addStatement("var layoutStr = %S", "")

            init.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { initArena ->")
                .beginControlFlow("try")
                .addStatement("layoutRaw = initArena.allocate(%L)", FFMConstants.XROSS_STRING_LAYOUT_CODE)
                .addStatement("layoutHandle.invokeExact(layoutRaw)")
                .beginControlFlow("if (layoutRaw != %T.NULL)", MEMORY_SEGMENT)
                .addStatement("val xs = %T(layoutRaw)", ClassName("$basePackage.xross.runtime", "XrossString"))
                .addStatement("layoutStr = xs.toString()")
                .endControlFlow()
                .nextControlFlow("catch (e: %T)", Throwable::class.asTypeName())
                .addStatement("throw %T(e)", RuntimeException::class.asTypeName())
                .endControlFlow()
                .beginControlFlow("if (layoutStr.isNotEmpty())")
                .addStatement("val parts = layoutStr.split(';')")
                .addStatement("this.STRUCT_SIZE = parts[0].toLong()")

            when (meta) {
                is XrossDefinition.Struct -> LayoutGenerator.buildStructLayoutInit(init, meta)
                is XrossDefinition.Enum -> LayoutGenerator.buildEnumLayoutInit(init, meta)
                is XrossDefinition.Opaque -> {}
            }

            init.beginControlFlow("if (layoutRaw != %T.NULL)", MEMORY_SEGMENT)
                .addStatement("xrossFreeStringHandle.invokeExact(layoutRaw)")
                .endControlFlow()
                .nextControlFlow("else")
                .addStatement("this.STRUCT_SIZE = 0L")
                .addStatement("this.LAYOUT = %T.structLayout()", MEMORY_LAYOUT)
                .endControlFlow() // if layoutStr.isNotEmpty
            init.endControlFlow() // use initArena
        }

        if (GeneratorUtils.isPureEnum(meta)) {
            init.add("\n// --- Enum Variant Instances ---\n")
            val baseClassName = GeneratorUtils.getClassName(meta.signature, basePackage)
            init.add("entries = listOf(\n")
            (meta as XrossDefinition.Enum).variants.forEach { v ->
                init.add("    %T(),\n", baseClassName.nestedClass(v.name))
            }
            init.add(")\n")
        }

        companionBuilder.addInitializerBlock(init.build())
    }

    private fun defineProperties(builder: TypeSpec.Builder, meta: XrossDefinition, basePackage: String) {
        val handles = mutableListOf<String>()

        builder.addProperty(
            PropertySpec.builder("linker", Linker::class.asTypeName(), KModifier.INTERNAL)
                .addAnnotation(JVM_FIELD)
                .initializer("%T.nativeLinker()", Linker::class.asTypeName())
                .build(),
        )

        if (meta !is XrossDefinition.Function) {
            handles.addAll(listOf("dropHandle", "layoutHandle", "xrossFreeStringHandle"))
            if (meta.methods.any { it.name == "clone" }) {
                handles.add("cloneHandle")
            }
        } else {
            handles.add("xrossFreeStringHandle")
        }

        when (meta) {
            is XrossDefinition.Struct -> {
                meta.methods.filter { it.isConstructor }.forEach { method ->
                    handles.add(GeneratorUtils.getHandleName(method))
                }
                meta.fields.forEach { field ->
                    val baseCamel = field.name.toCamelCase()
                    addPropertyHandles(handles, field, baseCamel)

                    builder.addProperty(PropertySpec.builder("VH_$baseCamel", VH_TYPE, KModifier.INTERNAL, KModifier.LATEINIT).addAnnotation(JVM_FIELD).mutable().build())
                    builder.addProperty(PropertySpec.builder("OFFSET_$baseCamel", Long::class.asTypeName(), KModifier.INTERNAL).addAnnotation(JVM_FIELD).mutable().initializer("0L").build())
                }
            }
            is XrossDefinition.Enum -> {
                handles.add("getTagHandle")
                handles.add("getVariantNameHandle")
                meta.variants.forEach { v ->
                    handles.add("new${v.name}Handle")
                    v.fields.forEach { f ->
                        val baseCamel = f.name.toCamelCase()
                        val combinedName = "${v.name}_$baseCamel"
                        addPropertyHandles(handles, f, combinedName)

                        if (!(f.ty is XrossType.Object && f.ty.ownership == XrossType.Ownership.Owned)) {
                            builder.addProperty(PropertySpec.builder("VH_$combinedName", VH_TYPE, KModifier.INTERNAL, KModifier.LATEINIT).addAnnotation(JVM_FIELD).mutable().build())
                        }
                        builder.addProperty(PropertySpec.builder("OFFSET_$combinedName", Long::class.asTypeName(), KModifier.INTERNAL).addAnnotation(JVM_FIELD).mutable().initializer("0L").build())
                    }
                }
            }
            is XrossDefinition.Opaque -> {
                meta.fields.forEach { field ->
                    val baseCamel = field.name.toCamelCase()
                    addPropertyHandles(handles, field, baseCamel, isOpaque = true)
                }
            }
            is XrossDefinition.Function -> {}
        }

        meta.methods.filter { !it.isConstructor }.forEach { handles.add("${it.name.toCamelCase()}Handle") }

        handles.distinct().forEach { name ->
            builder.addProperty(PropertySpec.builder(name, HANDLE_TYPE, KModifier.INTERNAL).addAnnotation(JVM_FIELD).mutable().build())
        }

        if (meta !is XrossDefinition.Function) {
            builder.addProperty(PropertySpec.builder("LAYOUT", LAYOUT_TYPE, KModifier.INTERNAL).addAnnotation(JVM_FIELD).mutable().initializer("%T.structLayout()", MEMORY_LAYOUT).build())
            builder.addProperty(PropertySpec.builder("STRUCT_SIZE", Long::class.asTypeName(), KModifier.INTERNAL).addAnnotation(JVM_FIELD).mutable().initializer("0L").build())
        }
    }

    private fun addPropertyHandles(handles: MutableList<String>, field: XrossField, baseCamel: String, isOpaque: Boolean = false) {
        when (field.ty) {
            is XrossType.RustString -> {
                handles.add("${baseCamel}StrGetHandle")
                handles.add("${baseCamel}StrSetHandle")
            }
            is XrossType.Optional -> {
                handles.add("${baseCamel}OptGetHandle")
                handles.add("${baseCamel}OptSetHandle")
            }
            is XrossType.Result -> {
                handles.add("${baseCamel}ResGetHandle")
                handles.add("${baseCamel}ResSetHandle")
            }
            else -> {
                if (isOpaque) {
                    handles.add("${baseCamel}GetHandle")
                    handles.add("${baseCamel}SetHandle")
                }
            }
        }
    }
}
