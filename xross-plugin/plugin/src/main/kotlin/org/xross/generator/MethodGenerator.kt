package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.GeneratorUtils
import org.xross.generator.util.addArgumentPreparation
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator

/**
 * Generates Kotlin methods that wrap native Rust functions using Java FFM.
 */
object MethodGenerator {
    private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
    private val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    /**
     * Generates Kotlin methods for all methods defined in the metadata.
     */
    fun generateMethods(
        classBuilder: TypeSpec.Builder,
        companionBuilder: TypeSpec.Builder,
        meta: XrossDefinition,
        basePackage: String,
    ) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        val isEnum = meta is XrossDefinition.Enum

        meta.methods.forEach { method ->
            if (method.isConstructor) {
                if (meta is XrossDefinition.Struct) {
                    ConstructorGenerator.generatePublicConstructor(
                        classBuilder,
                        companionBuilder,
                        method,
                        basePackage,
                    )
                }
                return@forEach
            }

            if (isEnum && method.name == "clone") return@forEach

            val returnType = GeneratorUtils.resolveReturnType(method.ret, basePackage)
            val kotlinName = method.name.toCamelCase().escapeKotlinKeyword()
            val funBuilder = FunSpec.builder(kotlinName).returns(returnType)
            if (method.isAsync) funBuilder.addModifiers(KModifier.SUSPEND)

            // Avoid clash with property accessors
            val fields = when (meta) {
                is XrossDefinition.Struct -> meta.fields
                is XrossDefinition.Opaque -> meta.fields
                else -> emptyList()
            }
            val hasClash = fields.any {
                val base = it.name.toCamelCase().replaceFirstChar { c -> c.uppercase() }
                kotlinName == "get$base" || kotlinName == "set$base"
            }
            if (hasClash) {
                funBuilder.addAnnotation(
                    AnnotationSpec.builder(JvmName::class)
                        .addMember("%S", "xross_${method.name.toCamelCase()}")
                        .build(),
                )
            }

            method.args.forEach { arg ->
                funBuilder.addParameter(
                    arg.name.toCamelCase().escapeKotlinKeyword(),
                    GeneratorUtils.resolveReturnType(arg.ty, basePackage),
                )
            }

            val body = CodeBlock.builder()
            if (method.methodType != XrossMethodType.Static) {
                body.addStatement("val currentSegment = this.segment")
                body.beginControlFlow("if (currentSegment == %T.NULL || !this.aliveFlag.isValid)", MEMORY_SEGMENT)
                body.addStatement("throw %T(%S)", NullPointerException::class.asTypeName(), "Object dropped or invalid")
                body.endControlFlow()
            }

            if (method.ret !is XrossType.Void) body.add("return ")

            body.beginControlFlow("try")
            val callArgs = mutableListOf<CodeBlock>()
            if (method.methodType != XrossMethodType.Static) callArgs.add(CodeBlock.of("currentSegment"))

            val argPrep = CodeBlock.builder()
            val needsArena = method.args.any { it.ty is XrossType.RustString || it.ty is XrossType.Optional || it.ty is XrossType.Result }

            if (needsArena) {
                argPrep.beginControlFlow("%T.ofConfined().use { arena ->", Arena::class.asTypeName())
            }

            method.args.forEach { arg ->
                val name = arg.name.toCamelCase().escapeKotlinKeyword()
                argPrep.addArgumentPreparation(arg.ty, name, callArgs, checkObjectValidity = true)
            }

            body.add(argPrep.build())

            val handleName = "${method.name.toCamelCase()}Handle"
            val isPanicable = method.handleMode is HandleMode.Panicable
            val call = if (method.isAsync || method.ret is XrossType.Result || isPanicable) {
                CodeBlock.of(
                    "$handleName.invokeExact(this.autoArena as %T, %L)",
                    SegmentAllocator::class,
                    callArgs.joinToCode(", "),
                )
            } else {
                CodeBlock.of("$handleName.invokeExact(%L)", callArgs.joinToCode(", "))
            }
            body.add(InvocationGenerator.applyMethodCall(method, call, returnType, selfType, basePackage, meta = meta))

            if (needsArena) body.endControlFlow()
            body.nextControlFlow("catch (e: Throwable)")
            val xrossException = ClassName("$basePackage.xross.runtime", "XrossException")
            body.addStatement("if (e is %T) throw e", xrossException)
            body.addStatement("throw %T(e)", RuntimeException::class.asTypeName())
            body.endControlFlow()

            funBuilder.addCode(body.build())
            if (method.methodType == XrossMethodType.Static) {
                companionBuilder.addFunction(funBuilder.build())
            } else {
                classBuilder.addFunction(funBuilder.build())
            }
        }
    }
}
