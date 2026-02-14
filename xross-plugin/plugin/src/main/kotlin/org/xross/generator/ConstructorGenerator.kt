package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.GeneratorUtils
import org.xross.generator.util.addArgumentPreparation
import org.xross.generator.util.addFactoryBody
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossMethod
import org.xross.structures.XrossType
import java.lang.foreign.Arena

object ConstructorGenerator {
    /**
     * Generates a public constructor for a struct.
     */
    fun generatePublicConstructor(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        method: XrossMethod,
        basePackage: String,
    ) {
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)

        val factoryBuilder = FunSpec.builder("xrossNewInternal").addModifiers(KModifier.PRIVATE)
            .addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        "argOf" + it.name.toCamelCase(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
            .returns(tripleType)

        val body = CodeBlock.builder()
        val callArgs = mutableListOf<CodeBlock>()
        val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }

        if (needsArena) {
            body.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class)
        }

        method.args.forEach { arg ->
            val name = "argOf" + arg.name.toCamelCase()
            body.addArgumentPreparation(arg.ty, name, callArgs)
        }

        body.addFactoryBody(
            basePackage,
            CodeBlock.of("newHandle.invokeExact(%L)", callArgs.joinToCode(", ")),
            CodeBlock.of("STRUCT_SIZE"),
            CodeBlock.of("dropHandle"),
        )
        body.addStatement(
            "return %T(res, %T(newAutoArena, newOwnerArena), flag)",
            Triple::class.asTypeName(),
            Pair::class.asTypeName(),
        )

        if (needsArena) {
            body.endControlFlow()
        }

        factoryBuilder.addCode(body.build())
        companionBuilder.addFunction(factoryBuilder.build())

        GeneratorUtils.addInternalConstructor(classBuilder, tripleType)

        classBuilder.addFunction(
            FunSpec.constructorBuilder().addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        "argOf" + it.name.toCamelCase(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
                .callThisConstructor(CodeBlock.of("xrossNewInternal(${method.args.joinToString(", ") { "argOf" + it.name.toCamelCase() }})"))
                .build(),
        )
    }
}
