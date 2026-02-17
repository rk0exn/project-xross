package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.GeneratorUtils
import org.xross.generator.util.addArenaAndFlag
import org.xross.generator.util.addFactoryBody
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.HandleMode
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
        selfType: ClassName,
    ) {
        val tripleType = GeneratorUtils.getFactoryTripleType(basePackage)
        val handleName = GeneratorUtils.getHandleName(method)
        val internalName = if (method.isDefault) {
            "xrossDefaultInternal"
        } else if (method.name == "new") {
            "xrossNewInternal"
        } else {
            "xrossNew${method.name.toCamelCase().replaceFirstChar { it.uppercase() }}Internal"
        }

        val factoryBuilder = FunSpec.builder(internalName).addModifiers(KModifier.PRIVATE)
            .addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        ("argOf" + it.name.toCamelCase()).escapeKotlinKeyword(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
            .addParameter(ParameterSpec.builder("externalArena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())
            .returns(tripleType)

        val body = CodeBlock.builder()
        body.addArenaAndFlag(basePackage, externalArena = CodeBlock.of("externalArena"))

        val callArgs = mutableListOf<CodeBlock>()
        val arenaForArg = GeneratorUtils.prepareArgumentsAndArena(method, body, basePackage, callArgs, namePrefix = "argOf")

        val isPanicable = method.handleMode is HandleMode.Panicable
        val handleCall = if (method.isAsync || method.ret is XrossType.Result || isPanicable) {
            if (isPanicable) {
                body.addStatement("val outPanic = (newOwnerArena as %T).allocate(%L)", java.lang.foreign.SegmentAllocator::class.asTypeName(), org.xross.generator.util.FFMConstants.XROSS_RESULT_LAYOUT_CODE)
                val pArgs = mutableListOf(CodeBlock.of("outPanic"))
                pArgs.addAll(callArgs)
                body.addStatement("$handleName.invokeExact(%L)", pArgs.joinToCode(", "))
                CodeBlock.of("outPanic")
            } else {
                CodeBlock.of("$handleName.invokeExact(newOwnerArena as %T, %L)", java.lang.foreign.SegmentAllocator::class.asTypeName(), callArgs.joinToCode(", "))
            }
        } else {
            CodeBlock.of("$handleName.invokeExact(%L)", callArgs.joinToCode(", "))
        }

        body.addFactoryBody(
            basePackage,
            handleCall,
            CodeBlock.of("STRUCT_SIZE"),
            CodeBlock.of("dropHandle"),
            handleMode = method.handleMode,
            externalArena = CodeBlock.of("externalArena"),
            defineArenaAndFlag = false,
        )

        // Triple(MemorySegment, XrossObject?, Boolean)
        body.addStatement(
            "return %T(res, null, isPersistentVal)",
            Triple::class.asTypeName(),
        )

        if (method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }) {
            body.endControlFlow()
        }

        factoryBuilder.addCode(body.build())
        companionBuilder.addFunction(factoryBuilder.build())

        val constructorParams = method.args.map {
            ParameterSpec.builder(
                ("argOf" + it.name.toCamelCase()).escapeKotlinKeyword(),
                GeneratorUtils.resolveReturnType(it.ty, basePackage),
            ).build()
        }.toMutableList()
        constructorParams.add(ParameterSpec.builder("arena", Arena::class.asTypeName().copy(nullable = true)).defaultValue("null").build())

        val callArgsString = method.args.joinToString(", ") { ("argOf" + it.name.toCamelCase()).escapeKotlinKeyword() }
        val finalCallArgs = if (callArgsString.isEmpty()) "externalArena = arena" else "$callArgsString, externalArena = arena"

        classBuilder.addFunction(
            FunSpec.constructorBuilder().addParameters(constructorParams)
                .callThisConstructor(CodeBlock.of("$internalName($finalCallArgs)"))
                .build(),
        )

        // Add 'use' function to companion
        val useFunName = if (method.isDefault || method.name == "new") "use" else "use${method.name.toCamelCase().replaceFirstChar { it.uppercase() }}"
        val useFunBuilder = FunSpec.builder(useFunName)
            .addParameters(
                method.args.map {
                    ParameterSpec.builder(
                        ("argOf" + it.name.toCamelCase()).escapeKotlinKeyword(),
                        GeneratorUtils.resolveReturnType(it.ty, basePackage),
                    ).build()
                },
            )
            .addTypeVariable(TypeVariableName("R"))
            .addParameter("block", LambdaTypeName.get(receiver = selfType, returnType = TypeVariableName("R")))
            .returns(TypeVariableName("R"))
            .addCode(
                CodeBlock.builder()
                    .add("return %T(%L).use { it.block() }\n", selfType, method.args.joinToString(", ") { ("argOf" + it.name.toCamelCase()).escapeKotlinKeyword() })
                    .build(),
            )
        companionBuilder.addFunction(useFunBuilder.build())
    }
}
