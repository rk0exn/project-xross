package org.xross.generator

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
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
    /**
     * Generates Kotlin source code for a given type definition.
     *
     * @param meta The type definition metadata.
     * @param outputDir The directory where the generated code will be written.
     * @param targetPackage The base package name for the generated code.
     * @param resolver The resolver for looking up other type definitions.
     */
    fun generate(
        meta: XrossDefinition,
        outputDir: File,
        targetPackage: String,
        resolver: TypeResolver,
    ) {
        val basePackage =
            if (meta.packageName.isEmpty()) {
                targetPackage
            } else {
                targetPackage.removeSuffix(meta.packageName).removeSuffix(".")
            }

        // 共通ランタイムの生成
        RuntimeGenerator.generate(outputDir, basePackage)

        when (val resolvedMeta = resolveAllTypes(meta, resolver)) {
            is XrossDefinition.Opaque -> {
                OpaqueGenerator.generateSingle(resolvedMeta, outputDir, targetPackage, basePackage)
            }

            is XrossDefinition.Struct, is XrossDefinition.Enum -> {
                generateComplexType(resolvedMeta, outputDir, targetPackage, basePackage)
            }

            is XrossDefinition.Function -> {
                generateFunction(resolvedMeta, outputDir, targetPackage, basePackage)
            }
        }
    }

    private fun resolveAllTypes(
        meta: XrossDefinition,
        resolver: TypeResolver,
    ): XrossDefinition = when (meta) {
        is XrossDefinition.Struct ->
            meta.copy(
                fields = meta.fields.map { it.copy(ty = resolveType(it.ty, resolver, meta.name)) },
                methods = resolveMethods(meta.methods, resolver, meta.name),
            )

        is XrossDefinition.Enum ->
            meta.copy(
                variants =
                meta.variants.map { v ->
                    v.copy(
                        fields =
                        v.fields.map {
                            it.copy(
                                ty =
                                resolveType(
                                    it.ty,
                                    resolver,
                                    "${meta.name}.${v.name}",
                                ),
                            )
                        },
                    )
                },
                methods = resolveMethods(meta.methods, resolver, meta.name),
            )

        is XrossDefinition.Opaque -> meta

        is XrossDefinition.Function ->
            meta.copy(
                method = resolveMethods(listOf(meta.method), resolver, meta.name).first(),
            )
    }

    private fun resolveMethods(methods: List<XrossMethod>, resolver: TypeResolver, context: String): List<XrossMethod> = methods.map { m ->
        m.copy(
            args = m.args.map { it.copy(ty = resolveType(it.ty, resolver, "$context.${m.name}")) },
            ret = resolveType(m.ret, resolver, "$context.${m.name}"),
        )
    }

    private fun resolveType(
        type: XrossType,
        resolver: TypeResolver,
        context: String,
    ): XrossType = when (type) {
        is XrossType.Object -> type.copy(signature = resolver.resolve(type.signature, context))
        is XrossType.Optional -> type.copy(inner = resolveType(type.inner, resolver, context))
        is XrossType.Result ->
            type.copy(
                ok = resolveType(type.ok, resolver, context),
                err = resolveType(type.err, resolver, context),
            )

        is XrossType.Async -> type.copy(inner = resolveType(type.inner, resolver, context))
        else -> type
    }

    private fun generateComplexType(
        meta: XrossDefinition,
        outputDir: File,
        targetPackage: String,
        basePackage: String,
    ) {
        val className = meta.name
        val isEnum = meta is XrossDefinition.Enum
        val isPure = GeneratorUtils.isPureEnum(meta)

        val classBuilder =
            when {
                meta is XrossDefinition.Struct -> {
                    TypeSpec.classBuilder(className).addSuperinterface(AutoCloseable::class)
                }

                isPure -> {
                    TypeSpec.enumBuilder(className)
                }

                isEnum -> {
                    TypeSpec
                        .classBuilder(className)
                        .addModifiers(KModifier.SEALED)
                        .addSuperinterface(AutoCloseable::class)
                }

                else -> throw IllegalArgumentException("Unsupported type")
            }

        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)
        CompanionGenerator.generateCompanions(companionBuilder, meta)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)

        when {
            meta is XrossDefinition.Struct ->
                PropertyGenerator.generateFields(
                    classBuilder,
                    meta,
                    basePackage,
                )

            isEnum ->
                EnumVariantGenerator.generateVariants(
                    classBuilder,
                    companionBuilder,
                    meta,
                    targetPackage,
                    basePackage,
                )
        }

        classBuilder.addType(companionBuilder.build())
        if (!isPure) {
            StructureGenerator.addFinalBlocks(classBuilder, meta)
        }

        GeneratorUtils.writeToDisk(classBuilder.build(), targetPackage, className, outputDir)
    }

    private fun generateFunction(
        meta: XrossDefinition.Function,
        outputDir: File,
        targetPackage: String,
        basePackage: String,
    ) {
        val className = meta.name.toCamelCase().replaceFirstChar { it.uppercase() }
        val classBuilder = TypeSpec.classBuilder(className)
            .addKdoc(meta.docs.joinToString("\n"))
            .primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).build())

        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)
        CompanionGenerator.generateCompanions(companionBuilder, meta)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)

        classBuilder.addType(companionBuilder.build())

        GeneratorUtils.writeToDisk(classBuilder.build(), targetPackage, className, outputDir)
    }
}
