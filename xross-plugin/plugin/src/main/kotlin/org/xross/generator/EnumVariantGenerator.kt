package org.xross.generator

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.xross.generator.util.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object EnumVariantGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    private fun addVariantFactoryMethod(
        companionBuilder: TypeSpec.Builder,
        factoryMethodName: String,
        handleName: String,
        tripleType: TypeName,
        basePackage: String,
        fields: List<XrossField> = emptyList(),
    ) {
        val runtimePkg = "$basePackage.xross.runtime"
        val aliveFlagType = ClassName(runtimePkg, "AliveFlag")

        companionBuilder.addFunction(
            FunSpec.builder(factoryMethodName)
                .addModifiers(KModifier.PRIVATE)
                .addParameters(
                    fields.map { field ->
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
                        val callArgs = mutableListOf<CodeBlock>()
                        val arenaForArg = GeneratorUtils.prepareArgumentsAndArena(fields, org.xross.structures.HandleMode.Normal, this, basePackage, callArgs, namePrefix = "argOf")

                        val handleCall = if (callArgs.isEmpty()) {
                            CodeBlock.of("$handleName.invokeExact()")
                        } else {
                            CodeBlock.of("$handleName.invokeExact(${callArgs.joinToString(", ")})")
                        }

                        addFactoryBody(
                            basePackage,
                            handleCall,
                            CodeBlock.of("STRUCT_SIZE"),
                            CodeBlock.of("dropHandle"),
                            isPersistent = true,
                            handleMode = org.xross.structures.HandleMode.Normal,
                        )

                        if (fields.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }) {
                            endControlFlow()
                        }

                        // entries マップ用のインスタンスは永続フラグを立てる
                        // addFactoryBody 内ですでに flag 変数が作成されているため、ここでは何も定義しない
                        fields.forEach { field ->
                            if (field.ty is XrossType.Object && field.ty.isOwned) {
                                val argName = "argOf" + field.name.toCamelCase()
                                addStatement("$argName.relinquish()")
                            }
                        }
                        addStatement("return %T(res, null, true)", Triple::class.asTypeName())
                    }.build(),
                )
                .build(),
        )
    }

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String,
        basePackage: String,
    ) {
        val baseClassName = ClassName(targetPackage, meta.name)
        val xrossObject = ClassName("$basePackage.xross.runtime", "XrossObject")
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)
        val variantTypeEnum = baseClassName.nestedClass("VariantType")

        val fromPointerBuilder = GeneratorUtils.buildFromPointerBase("fromPointer", baseClassName, basePackage)
        fromPointerBuilder.addCode(
            CodeBlock.builder()
                .beginControlFlow("val name = run")
                .addStatement("if (ptr == %T.NULL) throw %T(%S)", MEMORY_SEGMENT, NullPointerException::class.asTypeName(), "Pointer is NULL")
                .addStatement("val outBuf = java.lang.foreign.Arena.ofAuto().allocate(%L)", FFMConstants.XROSS_STRING_LAYOUT_CODE)
                .addStatement("getVariantNameHandle.invokeExact(outBuf, ptr)")
                .addRustStringResolution("outBuf", "n", basePackage = basePackage)
                .addStatement("n")
                .endControlFlow()
                .build(),
        )

        fromPointerBuilder.addStatement("val reinterpretedPtr = if (ptr.byteSize() >= STRUCT_SIZE) ptr else ptr.reinterpret(STRUCT_SIZE)")
        fromPointerBuilder.beginControlFlow("return when (name)")

        classBuilder.addType(
            TypeSpec.enumBuilder("VariantType")
                .apply {
                    meta.variants.forEach { addEnumConstant(it.name) }
                }
                .build(),
        )

        meta.variants.forEach { variant ->
            val variantClassName = baseClassName.nestedClass(variant.name)
            val isPureVariant = variant.fields.isEmpty()

            val variantTypeBuilder = TypeSpec.classBuilder(variant.name)
                .superclass(baseClassName)
                .addProperty(
                    PropertySpec.builder("variantType", variantTypeEnum)
                        .addModifiers(KModifier.OVERRIDE)
                        .getter(FunSpec.getterBuilder().addStatement("return %T.%N", variantTypeEnum, variant.name).build())
                        .build(),
                )

            GeneratorUtils.buildRawInitializer(variantTypeBuilder, xrossObject)
            GeneratorUtils.addInternalConstructor(variantTypeBuilder, tripleType)

            if (isPureVariant) {
                val factoryMethodName = "xrossNew${variant.name}Internal"
                addVariantFactoryMethod(
                    companionBuilder,
                    factoryMethodName,
                    "new${variant.name}Handle",
                    tripleType,
                    basePackage,
                )

                variantTypeBuilder.addFunction(
                    FunSpec.constructorBuilder()
                        .callThisConstructor(CodeBlock.of("$factoryMethodName()"))
                        .build(),
                )
            } else {
                val factoryMethodName = "xrossNew${variant.name}Internal"
                addVariantFactoryMethod(
                    companionBuilder,
                    factoryMethodName,
                    "new${variant.name}Handle",
                    tripleType,
                    basePackage,
                    variant.fields,
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

                val backingFields = mutableListOf<String>()
                variant.fields.forEach { field ->
                    val baseCamelName = field.name.toCamelCase()
                    val isPrimitive = field.ty !is XrossType.Object && field.ty !is XrossType.Optional && field.ty !is XrossType.Result
                    val combinedName = "${variant.name}_$baseCamelName"
                    val vhName = if (isPrimitive) "VH_$combinedName" else "null"
                    val offsetName = "OFFSET_$combinedName"
                    val kType = if (field.ty is XrossType.Object) {
                        GeneratorUtils.getClassName(field.ty.signature, basePackage)
                    } else {
                        field.ty.kotlinType
                    }

                    val backingFieldName = GeneratorUtils.addBackingPropertyIfNeeded(variantTypeBuilder, field, baseCamelName, kType)
                    if (backingFieldName != null) backingFields.add(backingFieldName)
                    val useLocks = field.safety != XrossThreadSafety.Direct && field.safety != XrossThreadSafety.Unsafe

                    variantTypeBuilder.addProperty(
                        PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                            .mutable(field.safety != XrossThreadSafety.Immutable)
                            .getter(GeneratorUtils.buildFullGetter(kType, buildVariantGetterBody(variant.name, field, vhName, offsetName, kType, baseClassName, backingFieldName, basePackage), useAsyncLock = useLocks))
                            .apply {
                                if (field.safety != XrossThreadSafety.Immutable) {
                                    setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildVariantSetterBody(variant.name, field, vhName, offsetName, kType, backingFieldName, basePackage), useAsyncLock = useLocks))
                                }
                            }
                            .build(),
                    )
                }

                GeneratorUtils.addClearCacheFunction(variantTypeBuilder, backingFields)
            }

            classBuilder.addType(variantTypeBuilder.build())
            fromPointerBuilder.addStatement("%S -> %T(reinterpretedPtr, parent = parent, isPersistent = isPersistent)", variant.name, variantClassName)
        }

        fromPointerBuilder.addStatement("else -> throw %T(%S + name)", RuntimeException::class.asTypeName(), "Unknown variant: ")
        fromPointerBuilder.endControlFlow()

        // entries を List に戻す
        companionBuilder.addProperty(
            PropertySpec.builder("entries", List::class.asClassName().parameterizedBy(baseClassName))
                .mutable(true)
                .initializer("emptyList()")
                .build(),
        )

        classBuilder.addProperty(
            PropertySpec.builder("variantType", variantTypeEnum)
                .addModifiers(KModifier.ABSTRACT)
                .build(),
        )

        companionBuilder.addFunction(fromPointerBuilder.build())
    }

    private fun buildVariantGetterBody(variantName: String, field: XrossField, vhName: String, offsetName: String, kType: TypeName, selfType: ClassName, backingFieldName: String?, basePackage: String): CodeBlock {
        val baseCamel = field.name.toCamelCase()
        val combinedName = "${variantName}_$baseCamel"
        return FieldBodyGenerator.buildGetterBody(
            FieldBodyGenerator.FieldContext(
                field,
                combinedName,
                vhName,
                offsetName,
                kType,
                selfType,
                backingFieldName,
                basePackage,
            ),
        )
    }

    private fun buildVariantSetterBody(variantName: String, field: XrossField, vhName: String, offsetName: String, kType: TypeName, backingFieldName: String?, basePackage: String): CodeBlock {
        val baseCamel = field.name.toCamelCase()
        val combinedName = "${variantName}_$baseCamel"
        return FieldBodyGenerator.buildSetterBody(
            FieldBodyGenerator.FieldContext(
                field,
                combinedName,
                vhName,
                offsetName,
                kType,
                ClassName("", "UNUSED"),
                backingFieldName,
                basePackage,
            ),
        )
    }
}
