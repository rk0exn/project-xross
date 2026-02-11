package org.xross.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossType
import java.io.File

object XrossGenerator {
    fun generate(meta: XrossDefinition, outputDir: File, targetPackage: String, resolver: TypeResolver) {
        val basePackage = if (meta.packageName.isEmpty()) {
            targetPackage
        } else {
            targetPackage.removeSuffix(meta.packageName).removeSuffix(".")
        }

        // 共通ランタイムの生成
        RuntimeGenerator.generate(outputDir, basePackage)

        when (val resolvedMeta = resolveAllTypes(meta, resolver)) {
            is XrossDefinition.Opaque -> {
                OpaqueGenerator.generateSingle(resolvedMeta, outputDir, targetPackage)
            }

            is XrossDefinition.Struct, is XrossDefinition.Enum -> {
                generateComplexType(resolvedMeta, outputDir, targetPackage, basePackage)
            }
        }
    }

    private fun resolveAllTypes(meta: XrossDefinition, resolver: TypeResolver): XrossDefinition {
        return when (meta) {
            is XrossDefinition.Struct -> meta.copy(
                fields = meta.fields.map { it.copy(ty = resolveType(it.ty, resolver, meta.name)) },
                methods = meta.methods.map { m ->
                    m.copy(
                        args = m.args.map { it.copy(ty = resolveType(it.ty, resolver, "${meta.name}.${m.name}")) },
                        ret = resolveType(m.ret, resolver, "${meta.name}.${m.name}")
                    )
                }
            )

            is XrossDefinition.Enum -> meta.copy(
                variants = meta.variants.map { v ->
                    v.copy(fields = v.fields.map {
                        it.copy(
                            ty = resolveType(
                                it.ty,
                                resolver,
                                "${meta.name}.${v.name}"
                            )
                        )
                    })
                },
                methods = meta.methods.map { m ->
                    m.copy(
                        args = m.args.map { it.copy(ty = resolveType(it.ty, resolver, "${meta.name}.${m.name}")) },
                        ret = resolveType(m.ret, resolver, "${meta.name}.${m.name}")
                    )
                }
            )

            is XrossDefinition.Opaque -> meta
        }
    }

    private fun resolveType(type: XrossType, resolver: TypeResolver, context: String): XrossType {
        return when (type) {
            is XrossType.Object -> type.copy(signature = resolver.resolve(type.signature, context))
            is XrossType.Optional -> type.copy(inner = resolveType(type.inner, resolver, context))
            is XrossType.Result -> type.copy(
                ok = resolveType(type.ok, resolver, context),
                err = resolveType(type.err, resolver, context)
            )

            is XrossType.Async -> type.copy(inner = resolveType(type.inner, resolver, context))
            else -> type
        }
    }

    private fun generateComplexType(
        meta: XrossDefinition,
        outputDir: File,
        targetPackage: String,
        basePackage: String
    ) {
        val className = meta.name
        val isEnum = meta is XrossDefinition.Enum
        val isPure = GeneratorUtils.isPureEnum(meta)

        val classBuilder = when {
            meta is XrossDefinition.Struct -> {
                TypeSpec.classBuilder(className).addSuperinterface(AutoCloseable::class)
            }

            isPure -> {
                TypeSpec.enumBuilder(className)
            }

            isEnum -> {
                TypeSpec.classBuilder(className).addModifiers(KModifier.SEALED)
                    .addSuperinterface(AutoCloseable::class)
            }

            else -> throw IllegalArgumentException("Unsupported type")
        }

        val companionBuilder = TypeSpec.companionObjectBuilder()
        StructureGenerator.buildBase(classBuilder, companionBuilder, meta, basePackage)
        CompanionGenerator.generateCompanions(companionBuilder, meta)
        MethodGenerator.generateMethods(classBuilder, companionBuilder, meta, basePackage)

        when {
            meta is XrossDefinition.Struct -> PropertyGenerator.generateFields(
                classBuilder,
                meta,
                basePackage
            )

            isEnum -> EnumVariantGenerator.generateVariants(
                classBuilder, companionBuilder, meta, targetPackage, basePackage
            )
        }

        classBuilder.addType(companionBuilder.build())
        if (!isPure) {
            StructureGenerator.addFinalBlocks(classBuilder, meta)
        }

        writeToDisk(classBuilder.build(), targetPackage, className, outputDir)
    }

    private fun writeToDisk(typeSpec: TypeSpec, pkg: String, name: String, outputDir: File) {
        val fileSpec = FileSpec.builder(pkg, name).addType(typeSpec).indent("    ").build()
        val content = cleanupPublic(fileSpec.toString())

        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("$name.kt").writeText(content)
    }

    /**
     * Kotlin においてデフォルト（省略可能）な public 修飾子を正規表現で一括削除する。
     */
    fun cleanupPublic(content: String): String {
        val keywords = listOf(
            "class", "interface", "fun", "val", "var", "object", "enum",
            "sealed", "open", "abstract", "constructor", "companion",
            "init", "data", "override", "lateinit", "inner"
        ).joinToString("|")

        val regex = Regex("""public\s+(?=$keywords)""")
        return content.replace(regex, "")
    }
}