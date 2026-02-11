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
import java.lang.ref.WeakReference

object EnumVariantGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String,
        basePackage: String
    ) {
        val isPure = GeneratorUtils.isPureEnum(meta)
        val baseClassName = ClassName(targetPackage, meta.name)
        val arenaPair = Pair::class.asClassName().parameterizedBy(Arena::class.asTypeName(), Arena::class.asTypeName().copy(nullable = true))
        val tripleType = Triple::class.asClassName().parameterizedBy(MEMORY_SEGMENT, arenaPair, ClassName("", "AliveFlag"))

        val fromPointerBuilder = FunSpec.builder("fromPointer")
            .addParameter("ptr", MEMORY_SEGMENT)
            .addParameter("autoArena", Arena::class.asTypeName())
            .addParameter(ParameterSpec.builder("confinedArena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
            .addParameter(ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true)).defaultValue("null").build())
            .returns(baseClassName)
            .addModifiers(KModifier.INTERNAL)

        if (isPure) {
            meta.variants.forEach { variant ->
                classBuilder.addEnumConstant(variant.name)
            }

            fromPointerBuilder.addCode(
                """
                try {
                    if (ptr == %T.NULL) throw %T("Pointer is NULL")
                    val nameRaw = Companion.getVariantNameHandle.invokeExact(ptr) as %T
                    val name = if (nameRaw == %T.NULL) "" else nameRaw.reinterpret(%T.MAX_VALUE).getString(0)
                    if (nameRaw != %T.NULL) Companion.xrossFreeStringHandle.invokeExact(nameRaw)
                    return valueOf(name)
                } finally {
                    if (confinedArena != null) {
                        confinedArena.close()
                    }
                }
                """.trimIndent(),
                MEMORY_SEGMENT, NullPointerException::class.asTypeName(), MEMORY_SEGMENT, MEMORY_SEGMENT, Long::class.asTypeName(), MEMORY_SEGMENT
            )
        } else {
            fromPointerBuilder.addStatement("""
                val name = run {
                    if (ptr == %T.NULL) throw %T(%S)
                    val nameRaw = Companion.getVariantNameHandle.invokeExact(ptr) as %T
                    val n = if (nameRaw == %T.NULL) "" else nameRaw.reinterpret(%T.MAX_VALUE).getString(0)
                    if (nameRaw != %T.NULL) Companion.xrossFreeStringHandle.invokeExact(nameRaw)
                    n
                }
            """.trimIndent(), MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL", MEMORY_SEGMENT, MEMORY_SEGMENT, Long::class.asTypeName(), MEMORY_SEGMENT)

            fromPointerBuilder.beginControlFlow("return when (name)")

            meta.variants.forEach { variant ->
                val variantClassName = baseClassName.nestedClass(variant.name)
                val isObject = variant.fields.isEmpty()

                if (isObject) {
                    val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                        .superclass(baseClassName)

                    variantTypeBuilder.primaryConstructor(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.INTERNAL)
                        .addParameter("raw", MEMORY_SEGMENT)
                        .addParameter("autoArena", Arena::class.asTypeName())
                        .addParameter(ParameterSpec.builder("confinedArena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
                        .addParameter(ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true)).defaultValue("null").build())
                        .build())
                    variantTypeBuilder.addSuperclassConstructorParameter("raw")
                    variantTypeBuilder.addSuperclassConstructorParameter("autoArena")
                    variantTypeBuilder.addSuperclassConstructorParameter("confinedArena")
                    variantTypeBuilder.addSuperclassConstructorParameter("sharedFlag")

                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    companionBuilder.addFunction(FunSpec.builder(factoryMethodName)
                        .addModifiers(KModifier.PRIVATE)
                        .returns(tripleType)
                        .addCode(CodeBlock.builder()
                            .addStatement("val newAutoArena = Arena.ofAuto()")
                            .addStatement("val newConfinedArena = Arena.ofConfined()")
                            .addStatement("val flag = AliveFlag(true)")
                            .addStatement("val resRaw = Companion.new${variant.name}Handle.invokeExact() as %T", MEMORY_SEGMENT)
                            .addStatement("val res = resRaw.reinterpret(Companion.STRUCT_SIZE, newAutoArena) { s -> if (flag.isValid) { flag.isValid = false; Companion.dropHandle.invokeExact(s); try { newConfinedArena.close() } catch (e: Throwable) {} } }")
                            .addStatement("return %T(res, %T(newAutoArena, newConfinedArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())
                            .build())
                        .build())

                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.PRIVATE)
                        .addParameter("p", tripleType)
                        .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second.first"), CodeBlock.of("p.second.second"), CodeBlock.of("p.third"))
                        .build())
                    
                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .callThisConstructor(CodeBlock.of("Companion.$factoryMethodName()"))
                        .build())

                    variantTypeBuilder.addFunction(FunSpec.builder("equals")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                        .returns(Boolean::class)
                        .addStatement("return other is %T", variantClassName)
                        .build())
                    
                    variantTypeBuilder.addFunction(FunSpec.builder("hashCode")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(Int::class)
                        .addStatement("return %S.hashCode()", variant.name)
                        .build())

                    classBuilder.addType(variantTypeBuilder.build())

                    fromPointerBuilder.addStatement("%S -> %T(ptr.reinterpret(Companion.STRUCT_SIZE), autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)", variant.name, variantClassName)
                } else {
                    fromPointerBuilder.addStatement("%S -> %T(ptr.reinterpret(Companion.STRUCT_SIZE), autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)", variant.name, variantClassName)

                    val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                    variantTypeBuilder.superclass(baseClassName)

                    variantTypeBuilder.primaryConstructor(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.INTERNAL)
                        .addParameter("raw", MEMORY_SEGMENT)
                        .addParameter("autoArena", Arena::class.asTypeName())
                        .addParameter(ParameterSpec.builder("confinedArena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
                        .addParameter(ParameterSpec.builder("sharedFlag", ClassName("", "AliveFlag").copy(nullable = true)).defaultValue("null").build())
                        .build())
                    variantTypeBuilder.addSuperclassConstructorParameter("raw")
                    variantTypeBuilder.addSuperclassConstructorParameter("autoArena")
                    variantTypeBuilder.addSuperclassConstructorParameter("confinedArena")
                    variantTypeBuilder.addSuperclassConstructorParameter("sharedFlag")

                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    companionBuilder.addFunction(FunSpec.builder(factoryMethodName)
                        .addModifiers(KModifier.PRIVATE)
                        .addParameters(variant.fields.map { field ->
                            val kType = if (field.ty is XrossType.Object) {
                                GeneratorUtils.getClassName(field.ty.signature, basePackage)
                            } else {
                                field.ty.kotlinType
                            }
                            ParameterSpec.builder("argOf" + field.name.toCamelCase(), kType).build()
                        })
                        .returns(tripleType)
                        .addCode(CodeBlock.builder()
                            .addStatement("val newAutoArena = Arena.ofAuto()")
                            .addStatement("val newConfinedArena = Arena.ofConfined()")
                            .addStatement("val flag = AliveFlag(true)")
                            .addStatement("val resRaw = Companion.new${variant.name}Handle.invokeExact(${variant.fields.joinToString(", ") { field ->
                                val argName = "argOf" + field.name.toCamelCase()
                                if (field.ty is XrossType.Bool) "if ($argName) 1.toByte() else 0.toByte()" 
                                else if (field.ty is XrossType.Object) "$argName.segment"
                                else argName
                            }}) as %T", MEMORY_SEGMENT)
                            .apply {
                                variant.fields.forEach { field ->
                                    if (field.ty is XrossType.Object && field.ty.isOwned) {
                                        val argName = "argOf" + field.name.toCamelCase()
                                        addStatement("$argName.close()")
                                    }
                                }
                            }
                            .addStatement("val res = resRaw.reinterpret(Companion.STRUCT_SIZE, newAutoArena) { s -> if (flag.isValid) { flag.isValid = false; Companion.dropHandle.invokeExact(s); try { newConfinedArena.close() } catch (e: Throwable) {} } }")
                            .addStatement("return %T(res, %T(newAutoArena, newConfinedArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())
                            .build())
                        .build())

                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .addModifiers(KModifier.PRIVATE)
                        .addParameter("p", tripleType)
                        .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second.first"), CodeBlock.of("p.second.second"), CodeBlock.of("p.third"))
                        .build())
                    
                    variantTypeBuilder.addFunction(FunSpec.constructorBuilder()
                        .addParameters(variant.fields.map { field ->
                            val kType = if (field.ty is XrossType.Object) {
                                GeneratorUtils.getClassName(field.ty.signature, basePackage)
                            } else {
                                field.ty.kotlinType
                            }
                            ParameterSpec.builder(field.name.toCamelCase().escapeKotlinKeyword(), kType).build()
                        })
                        .callThisConstructor(CodeBlock.of("Companion.$factoryMethodName(${variant.fields.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }})"))
                        .build())

                    val equalsBuilder = FunSpec.builder("equals")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                        .returns(Boolean::class)
                        .addStatement("if (this === other) return true")
                        .addStatement("if (other !is %T) return false", variantClassName)

                    variant.fields.forEach { field ->
                        val baseCamelName = field.name.toCamelCase()
                        val vhName = "VH_${variant.name}_$baseCamelName"
                        val offsetName = "OFFSET_${variant.name}_$baseCamelName"
                        val kType = if (field.ty is XrossType.Object) {
                            GeneratorUtils.getClassName(field.ty.signature, basePackage)
                        } else {
                            field.ty.kotlinType
                        }

                        var backingFieldName: String? = null
                        if (field.ty is XrossType.Object) {
                            backingFieldName = "_$baseCamelName"
                            val weakRefType = ClassName("java.lang.ref", "WeakReference").parameterizedBy(kType)
                            variantTypeBuilder.addProperty(PropertySpec.builder(backingFieldName, weakRefType.copy(nullable = true))
                                .mutable(true)
                                .addModifiers(KModifier.PRIVATE)
                                .initializer("null")
                                .build())
                        }

                        variantTypeBuilder.addProperty(PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                            .mutable(field.safety != XrossThreadSafety.Immutable)
                            .getter(buildVariantGetter(field, vhName, offsetName, kType, baseClassName, backingFieldName))
                            .apply {
                                if (field.safety != XrossThreadSafety.Immutable) {
                                    setter(buildVariantSetter(field, vhName, offsetName, kType, backingFieldName))
                                }
                            }
                            .build())
                        
                        equalsBuilder.addStatement("if (this.%L != other.%L) return false", baseCamelName.escapeKotlinKeyword(), baseCamelName.escapeKotlinKeyword())
                    }
                    
                    equalsBuilder.addStatement("return true")
                    variantTypeBuilder.addFunction(equalsBuilder.build())

                    classBuilder.addType(variantTypeBuilder.build())
                }
            }

            fromPointerBuilder.addStatement("else -> throw %T(%S + name)", RuntimeException::class.asTypeName(), "Unknown variant: ")
            fromPointerBuilder.endControlFlow()
        }

        companionBuilder.addFunction(fromPointerBuilder.build())
    }

    private fun buildVariantGetter(field: XrossField, vhName: String, offsetName: String, kType: TypeName, selfType: ClassName, backingFieldName: String?): FunSpec {
        val isSelf = kType == selfType
        val readCode = CodeBlock.builder()
            .addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Access error")
            .apply {
                when (field.ty) {
                    is XrossType.Bool -> addStatement("res = (Companion.$vhName.get(this.segment, Companion.$offsetName) as Byte) != (0).toByte()")
                    is XrossType.RustString -> {
                        addStatement("val rawSegment = Companion.$vhName.get(this.segment, Companion.$offsetName) as %T", MEMORY_SEGMENT)
                        addStatement("res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)", MEMORY_SEGMENT, Long::class.asTypeName())
                    }
                    is XrossType.Object -> {
                        addStatement("val cached = this.$backingFieldName?.get()")
                        beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                        addStatement("res = cached")
                        nextControlFlow("else")

                        if (field.ty.ownership == XrossType.Ownership.Owned) {
                            val sizeExpr = if (isSelf) CodeBlock.of("Companion.STRUCT_SIZE") else CodeBlock.of("%T.Companion.STRUCT_SIZE", kType)
                            addStatement("val resSeg = this.segment.asSlice(Companion.$offsetName, %L)", sizeExpr)
                            val fromPointerExpr = if (isSelf) CodeBlock.of("Companion.fromPointer") else CodeBlock.of("%T.Companion.fromPointer", kType)
                            addStatement("res = %L(resSeg, this.autoArena)", fromPointerExpr)
                        } else {
                            val sizeExpr = if (isSelf) "Companion.STRUCT_SIZE" else "%T.Companion.STRUCT_SIZE"
                            val fromPointerExpr = if (isSelf) "Companion.fromPointer" else "%T.Companion.fromPointer"
                            
                            val resolveArgs = mutableListOf<Any?>()
                            if (!isSelf) resolveArgs.add(kType)
                            resolveArgs.add(false) // isOwned = false

                            addStatement(
                                "val resSeg = resolveFieldSegment(this.segment, Companion.$vhName, Companion.$offsetName, $sizeExpr, %L)",
                                *resolveArgs.toTypedArray()
                            )

                            if (field.ty.ownership == XrossType.Ownership.Boxed) {
                                val fromPointerExprOwned = if (isSelf) CodeBlock.of("Companion.fromPointer") else CodeBlock.of("%T.Companion.fromPointer", kType)
                                addStatement("res = %L(resSeg, this.autoArena, confinedArena = null)", fromPointerExprOwned)
                            } else {
                                val fromPointerArgs = mutableListOf<Any?>()
                                if (!isSelf) fromPointerArgs.add(kType)
                                addStatement("res = $fromPointerExpr(resSeg, this.autoArena)", *fromPointerArgs.toTypedArray())
                            }
                        }
                        addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                        endControlFlow()
                    }
                    else -> addStatement("res = Companion.$vhName.get(this.segment, Companion.$offsetName) as %T", kType)
                }
            }.build()

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
        """.trimIndent(), kType, readCode, readCode
        ).build()
    }

    private fun buildVariantSetter(field: XrossField, vhName: String, offsetName: String, kType: TypeName, backingFieldName: String?): FunSpec {
        val isOwnedObject = field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned
        val writeCode = CodeBlock.builder()
            .addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Object invalid")
            .apply {
                if (isOwnedObject) {
                    addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Arg invalid")
                    addStatement("this.segment.asSlice(Companion.$offsetName, %T.Companion.STRUCT_SIZE).copyFrom(v.segment)", kType)
                } else if (field.ty is XrossType.Object) {
                    addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, v.segment)")
                } else if (field.ty is XrossType.Bool) {
                    addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, if (v) 1.toByte() else 0.toByte())")
                } else {
                    addStatement("Companion.$vhName.set(this.segment, Companion.$offsetName, v)")
                }

                if (backingFieldName != null) {
                    addStatement("this.$backingFieldName = null")
                }
            }.build()

        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
            """
            val stamp = this.sl.writeLock()
            try { 
                %L 
            } finally { this.sl.unlockWrite(stamp) }
        """.trimIndent(), writeCode
        ).build()
    }

    private fun generateAtomicPropertyInVariant(builder: TypeSpec.Builder, baseName: String, vhName: String, kType: TypeName, variantName: String) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(PropertySpec.builder("value", kType)
                .getter(FunSpec.getterBuilder().addStatement("return Companion.$vhName.getVolatile(this@${variantName}.segment, 0L) as %T", kType).build()).build())
            .addFunction(FunSpec.builder("update")
                .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                .beginControlFlow("while (true)")
                .beginControlFlow("try")
                .addStatement("val current = value")
                .addStatement("val next = block(current)")
                .beginControlFlow("if (Companion.$vhName.compareAndSet(this@${variantName}.segment, 0L, current, next))")
                .addStatement("return next")
                .endControlFlow()
                .nextControlFlow("catch (e: %T)", Throwable::class.asTypeName())
                .addStatement("throw e")
                .endControlFlow()
                .endControlFlow().build())
            .build()
        builder.addType(innerClass)
        builder.addProperty(PropertySpec.builder(baseName.escapeKotlinKeyword(), ClassName("", innerClassName)).initializer("%L()", innerClassName).build())
    }
}
