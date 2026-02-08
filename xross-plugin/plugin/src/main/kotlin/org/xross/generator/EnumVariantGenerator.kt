package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment
import java.lang.foreign.Arena

object EnumVariantGenerator {

    private fun resolveFqn(type: XrossType, meta: XrossDefinition, targetPackage: String): String {
        val signature = when (type) {
            is XrossType.Object -> type.signature
            else -> return (type.kotlinType as ClassName).canonicalName
        }
        return listOf(targetPackage, meta.packageName, signature)
            .filter { it.isNotEmpty() }
            .joinToString(".")
    }

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String
    ) {
        val baseClassName = ClassName(targetPackage, meta.name)
        val pairType = Pair::class.asClassName().parameterizedBy(MemorySegment::class.asClassName(), Arena::class.asClassName())

        val fromPointerBuilder = FunSpec.builder("fromPointer")
            .addParameter("ptr", MemorySegment::class)
            .addParameter("arena", Arena::class)
            .returns(baseClassName)
            .addModifiers(KModifier.INTERNAL)
            .addCode(
                """
                if (ptr == %T.NULL) throw %T("Pointer is NULL")
                val nameRaw = get_variant_nameHandle.invokeExact(ptr) as %T
                val name = if (nameRaw == %T.NULL) "" else nameRaw.reinterpret(%T.MAX_VALUE).getString(0)
                if (nameRaw != %T.NULL) xross_free_stringHandle.invokeExact(nameRaw)
                return when (name) {
                """.trimIndent(),
                MemorySegment::class, NullPointerException::class, MemorySegment::class, MemorySegment::class, Long::class, MemorySegment::class
            )

        meta.variants.forEach { variant ->
            fromPointerBuilder.addCode("    %S -> %L(ptr.reinterpret(STRUCT_SIZE), arena)\n", variant.name, variant.name)

            val isObject = variant.fields.isEmpty()
            val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
            variantTypeBuilder.superclass(baseClassName)

            variantTypeBuilder.primaryConstructor(FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("raw", MemorySegment::class)
                .addParameter("arena", Arena::class)
                .build())
            variantTypeBuilder.addSuperclassConstructorParameter("raw")
            variantTypeBuilder.addSuperclassConstructorParameter("arena")

            val factoryMethodName = "xross_new_${variant.name}_internal"
            val callInvokeArgs = variant.fields.joinToString(", ") { field ->
                val argName = "arg_" + field.name.toCamelCase()
                when (field.ty) {
                    is XrossType.Object -> "$argName.segment"
                    is XrossType.Bool -> "if ($argName) 1.toByte() else 0.toByte()"
                    else -> argName
                }
            }

            val factoryBody = CodeBlock.builder()
                .addStatement("val newArena = Arena.ofAuto()")
                .addStatement("val resRaw = new_${variant.name}Handle.invokeExact($callInvokeArgs) as %T", MemorySegment::class)
                .addStatement("val res = resRaw.reinterpret(STRUCT_SIZE, newArena) { s -> dropHandle.invokeExact(s) }")
                .addStatement("return res to newArena")

            companionBuilder.addFunction(FunSpec.builder(factoryMethodName)
                .addModifiers(KModifier.PRIVATE)
                .addParameters(variant.fields.map { field ->
                    ParameterSpec.builder("arg_" + field.name.toCamelCase(), resolveFqn(field.ty, meta, targetPackage).let { fqn ->
                        val lastDot = fqn.lastIndexOf('.')
                        if (lastDot == -1) ClassName("", fqn)
                        else ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
                    }).build()
                })
                .returns(pairType)
                .addCode(factoryBody.build())
                .build())

            variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter("p", pairType)
                .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second"))
                .build())
            
            variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                .addParameters(variant.fields.map { field ->
                    ParameterSpec.builder(field.name.toCamelCase().escapeKotlinKeyword(), resolveFqn(field.ty, meta, targetPackage).let { fqn ->
                        val lastDot = fqn.lastIndexOf('.')
                        if (lastDot == -1) ClassName("", fqn)
                        else ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
                    }).build()
                })
                .callThisConstructor(CodeBlock.of("Companion.$factoryMethodName(${variant.fields.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }})"))
                .build())

            if (isObject) {
                companionBuilder.addProperty(PropertySpec.builder(variant.name, ClassName("", variant.name))
                    .getter(FunSpec.getterBuilder().addStatement("return %L()", variant.name).build())
                    .build())
            }

            variant.fields.forEach { field ->
                val baseCamelName = field.name.toCamelCase()
                val vhName = "VH_${variant.name}_$baseCamelName"
                val offsetName = "OFFSET_${variant.name}_$baseCamelName"
                val fqn = resolveFqn(field.ty, meta, targetPackage)
                val kType = TypeVariableName(" $fqn")

                if (field.safety == XrossThreadSafety.Atomic) {
                    generateAtomicPropertyInVariant(variantTypeBuilder, baseCamelName, vhName, fqn)
                } else {
                    val isMutable = field.safety != XrossThreadSafety.Immutable
                    val propBuilder = PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                        .mutable(isMutable)
                        .getter(buildVariantGetter(field, vhName, offsetName, fqn))
                    if (isMutable) propBuilder.setter(buildVariantSetter(field, vhName, fqn))
                    variantTypeBuilder.addProperty(propBuilder.build())
                }
            }
            classBuilder.addType(variantTypeBuilder.build())
        }

        fromPointerBuilder.addCode("    else -> throw %T(%S + name)\n", RuntimeException::class, "Unknown variant: ")
        fromPointerBuilder.addCode("}")
        companionBuilder.addFunction(fromPointerBuilder.build())
    }

    private fun buildVariantGetter(field: XrossField, vhName: String, offsetName: String, fqn: String): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Attempted to access field '${field.name}' on a NULL or invalid native object")

        when (field.ty) {
            is XrossType.Bool -> readCodeBuilder.addStatement("res = (Companion.$vhName.get(this.segment, 0L) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement("val rawSegment = Companion.$vhName.get(this.segment, 0L) as %T", MemorySegment::class)
                readCodeBuilder.addStatement("res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)", MemorySegment::class, Long::class)
            }
            is XrossType.Object -> {
                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    readCodeBuilder.addStatement("val resSeg = this.segment.asSlice(Companion.$offsetName, %L.Companion.STRUCT_SIZE)", fqn)
                    readCodeBuilder.addStatement("res = %L(resSeg, arena = this.arena, isArenaOwner = false)", fqn)
                } else {
                    readCodeBuilder.addStatement("val rawSegment = Companion.$vhName.get(this.segment, 0L) as %T", MemorySegment::class)
                    if (field.ty.ownership == XrossType.Ownership.Boxed) {
                        readCodeBuilder.addStatement("val retArena = Arena.ofConfined()")
                        readCodeBuilder.addStatement("val resSeg = rawSegment.reinterpret(%L.Companion.STRUCT_SIZE, retArena) { s -> %L.Companion.dropHandle.invokeExact(s) }", fqn, fqn)
                        readCodeBuilder.addStatement("res = %L(resSeg, arena = retArena, isArenaOwner = true)", fqn)
                    } else {
                        readCodeBuilder.addStatement("val resSeg = if (rawSegment == %T.NULL) %T.NULL else rawSegment.reinterpret(%L.Companion.STRUCT_SIZE)", MemorySegment::class, MemorySegment::class, fqn)
                        readCodeBuilder.addStatement("res = %L(resSeg, arena = this.arena, isArenaOwner = false)", fqn)
                    }
                }
            }
            else -> readCodeBuilder.addStatement("res = Companion.$vhName.get(this.segment, 0L) as %L", fqn)
        }

        return FunSpec.getterBuilder().addCode(
            """
            var stamp = this.sl.tryOptimisticRead()
            var res: %T
            // Optimistic read
            %L
            if (!this.sl.validate(stamp)) {
                stamp = this.sl.readLock()
                try { 
                    // Pessimistic read
                    %L
                } finally { this.sl.unlockRead(stamp) }
            }
            return res
        """.trimIndent(), TypeVariableName(" $fqn"), readCodeBuilder.build(), readCodeBuilder.build()
        ).build()
    }

    private fun buildVariantSetter(field: XrossField, vhName: String, fqn: String): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Attempted to set field '${field.name}' on a NULL or invalid native object")

        when (field.ty) {
            is XrossType.Bool -> writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Cannot set field '${field.name}' with a NULL or invalid native reference")
                writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, v.segment)")
            }
            else -> writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, v)")
        }

        return FunSpec.setterBuilder().addParameter("v", TypeVariableName(" $fqn")).addCode(
            """
            val stamp = this.sl.writeLock()
            try { %L } finally { this.sl.unlockWrite(stamp) }
        """.trimIndent(), writeCodeBuilder.build()
        ).build()
    }

    private fun generateAtomicPropertyInVariant(builder: TypeSpec.Builder, baseName: String, vhName: String, fqn: String) {
        val kType = TypeVariableName(" $fqn")
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(PropertySpec.builder("value", kType)
                .getter(FunSpec.getterBuilder().addStatement("return Companion.$vhName.getVolatile(this@${variantName(builder)}.segment, 0L) as $fqn").build()).build())
            .addFunction(FunSpec.builder("update")
                .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                .beginControlFlow("while (true)")
                .beginControlFlow("try")
                .addStatement("val current = value")
                .addStatement("val next = block(current)")
                .beginControlFlow("if (Companion.$vhName.compareAndSet(this@${variantName(builder)}.segment, 0L, current, next))")
                .addStatement("return next")
                .endControlFlow()
                .nextControlFlow("catch (e: %T)", Throwable::class)
                .addStatement("throw e")
                .endControlFlow()
                .endControlFlow().build())
            .build()
        builder.addType(innerClass)
        builder.addProperty(PropertySpec.builder(baseName.escapeKotlinKeyword(), ClassName("", innerClassName)).initializer("%L()", innerClassName).build())
    }
    private fun variantName(builder: TypeSpec.Builder): String = builder.build().name!!
}