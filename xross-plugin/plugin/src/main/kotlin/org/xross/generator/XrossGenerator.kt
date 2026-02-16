package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.GeneratorUtils
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossMethod
import org.xross.structures.XrossType
import java.io.File

/**
 * Main entry point for generating Kotlin bindings from Xross metadata.
 */
object XrossGenerator {
    fun generate(
        meta: XrossDefinition,
        outputDir: File,
        targetPackage: String,
        resolver: TypeResolver,
    ) {
        val basePackage = if (meta.packageName.isEmpty()) targetPackage else targetPackage.removeSuffix(meta.packageName).removeSuffix(".")
        RuntimeGenerator.generate(outputDir, basePackage)

        when (val resolvedMeta = resolveAllTypes(meta, resolver)) {
            is XrossDefinition.Opaque -> OpaqueGenerator.generateSingle(resolvedMeta, outputDir, targetPackage, basePackage)
            is XrossDefinition.Struct, is XrossDefinition.Enum -> generateComplexType(resolvedMeta, outputDir, targetPackage, basePackage)
            is XrossDefinition.Function -> generateFunction(resolvedMeta, outputDir, targetPackage, basePackage)
        }
    }

    private fun resolveAllTypes(meta: XrossDefinition, resolver: TypeResolver): XrossDefinition = when (meta) {
        is XrossDefinition.Struct -> meta.copy(fields = meta.fields.map { it.copy(ty = resolveType(it.ty, resolver, meta.name)) }, methods = resolveMethods(meta.methods, resolver, meta.name))
        is XrossDefinition.Enum -> meta.copy(variants = meta.variants.map { v -> v.copy(fields = v.fields.map { it.copy(ty = resolveType(it.ty, resolver, "${meta.name}.${v.name}")) }) }, methods = resolveMethods(meta.methods, resolver, meta.name))
        is XrossDefinition.Opaque -> meta
        is XrossDefinition.Function -> meta.copy(method = resolveMethods(listOf(meta.method), resolver, meta.name).first())
    }

    private fun resolveMethods(methods: List<XrossMethod>, resolver: TypeResolver, context: String): List<XrossMethod> = methods.map { m ->
        m.copy(args = m.args.map { it.copy(ty = resolveType(it.ty, resolver, "$context.${m.name}")) }, ret = resolveType(m.ret, resolver, "$context.${m.name}"))
    }

    private fun resolveType(type: XrossType, resolver: TypeResolver, context: String): XrossType = when (type) {
        is XrossType.Object -> type.copy(signature = resolver.resolve(type.signature, context))
        is XrossType.Optional -> type.copy(inner = resolveType(type.inner, resolver, context))
        is XrossType.Result -> type.copy(ok = resolveType(type.ok, resolver, context), err = resolveType(type.err, resolver, context))
        is XrossType.Async -> type.copy(inner = resolveType(type.inner, resolver, context))
        else -> type
    }

    private fun generateComplexType(meta: XrossDefinition, outputDir: File, targetPackage: String, basePackage: String) {
        val className = meta.name
        val isEnum = meta is XrossDefinition.Enum

        val classBuilder = if (isEnum) {
            TypeSpec.classBuilder(className).addModifiers(KModifier.SEALED)
        } else {
            TypeSpec.classBuilder(className)
        }

        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)

        // 呼び出し順序を変更: Variant/Field 生成を先に行う
        when {
            meta is XrossDefinition.Struct -> PropertyGenerator.generateFields(classBuilder, meta, basePackage)
            isEnum -> EnumVariantGenerator.generateVariants(
                classBuilder,
                companionBuilder,
                meta,
                targetPackage,
                basePackage,
            )
        }

        CompanionGenerator.generateCompanions(companionBuilder, meta, basePackage)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)

        if (meta is XrossDefinition.Struct || meta is XrossDefinition.Opaque) {
            GeneratorUtils.addInternalConstructor(classBuilder, GeneratorUtils.getFactoryTripleType(basePackage))
        }

        classBuilder.addType(companionBuilder.build())
        StructureGenerator.addFinalBlocks(classBuilder, meta)

        val runtimePkg = "$basePackage.xross.runtime"
        val fileSpecBuilder = FileSpec.builder(targetPackage, className)
            .addImport(runtimePkg, "AliveFlag", "XrossException", "XrossObject", "XrossNativeObject", "XrossRuntime")

        val fileSpec = fileSpecBuilder
            .addType(classBuilder.build())
            .indent("    ")
            .build()

        GeneratorUtils.writeToDisk(fileSpec, outputDir)
    }

    private fun generateFunction(meta: XrossDefinition.Function, outputDir: File, targetPackage: String, basePackage: String) {
        val className = meta.name.toCamelCase().replaceFirstChar { it.uppercase() }
        val classBuilder = TypeSpec.classBuilder(className).addKdoc(meta.docs.joinToString("\n")).primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).build())
        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)
        CompanionGenerator.generateCompanions(companionBuilder, meta, basePackage)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)
        classBuilder.addType(companionBuilder.build())

        val runtimePkg = "$basePackage.xross.runtime"
        val fileSpec = FileSpec.builder(targetPackage, className)
            .addImport(runtimePkg, "AliveFlag", "XrossException", "XrossObject", "XrossNativeObject", "XrossRuntime")
            .addType(classBuilder.build())
            .indent("    ")
            .build()

        GeneratorUtils.writeToDisk(fileSpec, outputDir)
    }
}
