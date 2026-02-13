package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.ref.WeakReference

object EnumVariantGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    private fun TypeSpec.Builder.buildRawInitializer(aliveFlagType: ClassName) {
        this.primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.INTERNAL)
                .addParameter("raw", MEMORY_SEGMENT)
                .addParameter("autoArena", Arena::class.asTypeName())
                .addParameter(ParameterSpec.builder("confinedArena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
                .addParameter(ParameterSpec.builder("sharedFlag", aliveFlagType.copy(nullable = true)).defaultValue("null").build())
                .build(),
        )
        this.addSuperclassConstructorParameter("raw")
        this.addSuperclassConstructorParameter("autoArena")
        this.addSuperclassConstructorParameter("confinedArena")
        this.addSuperclassConstructorParameter("sharedFlag")
    }

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String,
        basePackage: String,
    ) {
        val isPure = GeneratorUtils.isPureEnum(meta)
        val baseClassName = ClassName(targetPackage, meta.name)
        val aliveFlagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)

        val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", baseClassName, basePackage)

        if (isPure) {
            meta.variants.forEach { variant ->
                classBuilder.addEnumConstant(variant.name)
            }
            fromPointerBuilder.addCode(
                CodeBlock.builder()
                    .beginControlFlow("try")
                    .addStatement("if (ptr == %T.NULL) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL")
                    .addRustStringResolution("getVariantNameHandle.invokeExact(ptr)", "name")
                    .addStatement("return valueOf(name)")
                    .nextControlFlow("finally")
                    .add("// Manual close is removed because we use Arena.ofAuto() for safety\n")
                    .endControlFlow()
                    .build(),
            )
        } else {
            fromPointerBuilder.addCode(
                CodeBlock.builder()
                    .beginControlFlow("val name = run")
                    .addStatement("if (ptr == %T.NULL) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL")
                    .addRustStringResolution("getVariantNameHandle.invokeExact(ptr)", "n")
                    .addStatement("n")
                    .endControlFlow()
                    .build(),
            )

            fromPointerBuilder.addStatement("val reinterpretedPtr = if (ptr.byteSize() >= STRUCT_SIZE) ptr else ptr.reinterpret(STRUCT_SIZE)")
            fromPointerBuilder.beginControlFlow("return when (name)")

            meta.variants.forEach { variant ->
                val variantClassName = baseClassName.nestedClass(variant.name)
                val isObject = variant.fields.isEmpty()

                if (isObject) {
                    val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                        .superclass(baseClassName)

                    variantTypeBuilder.buildRawInitializer(aliveFlagType)
                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    companionBuilder.addFunction(
                        FunSpec.builder(factoryMethodName)
                            .addModifiers(KModifier.PRIVATE)
                            .returns(tripleType)
                            .addCode(
                                CodeBlock.builder().apply {
                                    GeneratorUtils.addFactoryBody(
                                        this,
                                        basePackage,
                                        CodeBlock.of("new${variant.name}Handle.invokeExact()"),
                                        CodeBlock.of("STRUCT_SIZE"),
                                        CodeBlock.of("dropHandle"),
                                    )
                                    addStatement("return %T(res, %T(newAutoArena, newOwnerArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())
                                }.build(),
                            )
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .addModifiers(KModifier.PRIVATE)
                            .addParameter("p", tripleType)
                            .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second.first"), CodeBlock.of("p.second.second"), CodeBlock.of("p.third"))
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .callThisConstructor(CodeBlock.of("$factoryMethodName()"))
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.builder("equals")
                            .addModifiers(KModifier.OVERRIDE)
                            .addParameter("other", Any::class.asTypeName().copy(nullable = true))
                            .returns(Boolean::class)
                            .addStatement("return other is %T", variantClassName)
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.builder("hashCode")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(Int::class)
                            .addStatement("return %S.hashCode()", variant.name)
                            .build(),
                    )

                    classBuilder.addType(variantTypeBuilder.build())

                    fromPointerBuilder.addStatement("%S -> %T(reinterpretedPtr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)", variant.name, variantClassName)
                } else {
                    fromPointerBuilder.addStatement("%S -> %T(reinterpretedPtr, autoArena, confinedArena = confinedArena, sharedFlag = sharedFlag)", variant.name, variantClassName)

                    val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                    variantTypeBuilder.superclass(baseClassName)

                    variantTypeBuilder.buildRawInitializer(aliveFlagType)
                    val factoryMethodName = "xrossNew${variant.name}Internal"
                    companionBuilder.addFunction(
                        FunSpec.builder(factoryMethodName)
                            .addModifiers(KModifier.PRIVATE)
                            .addParameters(
                                variant.fields.map { field ->
                                    val kType = if (field.ty is XrossType.Object) {
                                        GeneratorUtils.getClassName(field.ty.signature, basePackage)
                                    } else {
                                        field.ty.kotlinType
                                    }
                                    ParameterSpec.builder("argOf" + field.name.toCamelCase(), kType).build()
                                },
                            )
                            .returns(tripleType)
                            .addCode(
                                CodeBlock.builder().apply {
                                    val handleCall = CodeBlock.of(
                                        "new${variant.name}Handle.invokeExact(${variant.fields.joinToString(", ") { field ->
                                            val argName = "argOf" + field.name.toCamelCase()
                                            when (field.ty) {
                                                is XrossType.Bool -> {
                                                    "if ($argName) 1.toByte() else 0.toByte()"
                                                }

                                                is XrossType.Object -> {
                                                    "$argName.segment"
                                                }

                                                else -> {
                                                    argName
                                                }
                                            }
                                        }})",
                                    )
                                    GeneratorUtils.addFactoryBody(
                                        this,
                                        basePackage,
                                        handleCall,
                                        CodeBlock.of("STRUCT_SIZE"),
                                        CodeBlock.of("dropHandle"),
                                    )
                                    variant.fields.forEach { field ->
                                        if (field.ty is XrossType.Object && field.ty.isOwned) {
                                            val argName = "argOf" + field.name.toCamelCase()
                                            addStatement("$argName.relinquish()")
                                        }
                                    }
                                    addStatement("return %T(res, %T(newAutoArena, newOwnerArena), flag)", Triple::class.asTypeName(), Pair::class.asTypeName())
                                }.build(),
                            )
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .addModifiers(KModifier.PRIVATE)
                            .addParameter("p", tripleType)
                            .callThisConstructor(CodeBlock.of("p.first"), CodeBlock.of("p.second.first"), CodeBlock.of("p.second.second"), CodeBlock.of("p.third"))
                            .build(),
                    )

                    variantTypeBuilder.addFunction(
                        FunSpec.constructorBuilder()
                            .addParameters(
                                variant.fields.map { field ->
                                    val kType = if (field.ty is XrossType.Object) {
                                        GeneratorUtils.getClassName(field.ty.signature, basePackage)
                                    } else {
                                        field.ty.kotlinType
                                    }
                                    ParameterSpec.builder(field.name.toCamelCase().escapeKotlinKeyword(), kType).build()
                                },
                            )
                            .callThisConstructor(CodeBlock.of("$factoryMethodName(${variant.fields.joinToString(", ") { it.name.toCamelCase().escapeKotlinKeyword() }})"))
                            .build(),
                    )

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
                            variantTypeBuilder.addProperty(
                                PropertySpec.builder(backingFieldName, weakRefType.copy(nullable = true))
                                    .mutable(true)
                                    .addModifiers(KModifier.PRIVATE)
                                    .initializer("null")
                                    .build(),
                            )
                        }

                        variantTypeBuilder.addProperty(
                            PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                                .mutable(field.safety != XrossThreadSafety.Immutable)
                                .getter(buildVariantGetter(field, vhName, offsetName, kType, baseClassName, backingFieldName, basePackage))
                                .apply {
                                    if (field.safety != XrossThreadSafety.Immutable) {
                                        setter(buildVariantSetter(field, vhName, offsetName, kType, backingFieldName))
                                    }
                                }
                                .build(),
                        )

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

    private fun buildVariantGetter(field: XrossField, vhName: String, offsetName: String, kType: TypeName, selfType: ClassName, backingFieldName: String?, basePackage: String): FunSpec {
        val isSelf = kType == selfType
        val readCode = CodeBlock.builder()
            .addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Access error")
            .apply {
                when (field.ty) {
                    is XrossType.Bool -> addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
                    is XrossType.RustString -> {
                        val callExpr = "$vhName.get(this.segment, $offsetName)"
                        addRustStringResolution(callExpr, "res", isAssignment = true, shouldFree = false)
                    }
                    is XrossType.Object -> {
                        if (backingFieldName != null) {
                            addStatement("val cached = this.$backingFieldName?.get()")
                            beginControlFlow("if (cached != null && cached.aliveFlag.isValid)")
                            addStatement("res = cached")
                            nextControlFlow("else")
                        }

                        val flagType = ClassName("$basePackage.xross.runtime", "AliveFlag")
                        if (field.ty.ownership == XrossType.Ownership.Owned) {
                            val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                            addStatement("val resSeg = this.segment.asSlice($offsetName, %L)", sizeExpr)
                            val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                            addStatement("res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                        } else {
                            val sizeExpr = if (isSelf) CodeBlock.of("STRUCT_SIZE") else CodeBlock.of("%T.STRUCT_SIZE", kType)
                            val fromPointerExpr = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                            val ffiHelpers = ClassName("$basePackage.xross.runtime", "FfiHelpers")

                            addStatement(
                                "val resSeg = %T.resolveFieldSegment(this.segment, $vhName, $offsetName, %L, %L)",
                                ffiHelpers,
                                sizeExpr,
                                false, // isOwned = false
                            )

                            if (field.ty.ownership == XrossType.Ownership.Boxed) {
                                val fromPointerExprOwned = if (isSelf) CodeBlock.of("fromPointer") else CodeBlock.of("%T.fromPointer", kType)
                                addStatement("res = %L(resSeg, this.autoArena, confinedArena = null, sharedFlag = %T(true, this.aliveFlag))", fromPointerExprOwned, flagType)
                            } else {
                                addStatement("res = %L(resSeg, this.autoArena, sharedFlag = %T(true, this.aliveFlag))", fromPointerExpr, flagType)
                            }
                        }

                        if (backingFieldName != null) {
                            addStatement("this.$backingFieldName = %T(res)", WeakReference::class.asTypeName())
                            endControlFlow()
                        }
                    }
                    else -> addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
                }
            }.build()

        return GeneratorUtils.buildOptimisticReadGetter(kType, readCode)
    }

    private fun buildVariantSetter(field: XrossField, vhName: String, offsetName: String, kType: TypeName, backingFieldName: String?): FunSpec {
        val isOwnedObject = field.ty is XrossType.Object && field.ty.ownership == XrossType.Ownership.Owned
        val writeCode = CodeBlock.builder()
            .addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Object invalid")
            .apply {
                if (isOwnedObject) {
                    addStatement("if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Arg invalid")
                    addStatement("this.segment.asSlice($offsetName, %T.STRUCT_SIZE).copyFrom(v.segment)", kType)
                } else if (field.ty is XrossType.Object) {
                    addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                } else if (field.ty is XrossType.Bool) {
                    addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
                } else {
                    addStatement("$vhName.set(this.segment, $offsetName, v)")
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
            """.trimIndent(),
            writeCode,
        ).build()
    }
}
