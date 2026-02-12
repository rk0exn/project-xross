package org.xross.generator

import com.squareup.kotlinpoet.*
import java.io.File
import java.lang.foreign.MemorySegment

object RuntimeGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    fun generate(outputDir: File, basePackage: String) {
        val pkg = "$basePackage.xross.runtime"

        // --- XrossException ---
        val xrossException = TypeSpec.classBuilder("XrossException")
            .superclass(Throwable::class)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("error", Any::class)
                    .build(),
            )
            .addProperty(PropertySpec.builder("error", Any::class).initializer("error").build())
            .build()

        // --- AliveFlag ---
        val aliveFlag = TypeSpec.classBuilder("AliveFlag")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("initial", Boolean::class)
                    .addParameter(ParameterSpec.builder("parent", ClassName(pkg, "AliveFlag").copy(nullable = true)).defaultValue("null").build())
                    .build(),
            )
            .addProperty(PropertySpec.builder("parent", ClassName(pkg, "AliveFlag").copy(nullable = true), KModifier.PRIVATE).initializer("parent").build())
            .addProperty(
                PropertySpec.builder("_isValid", ClassName("java.util.concurrent.atomic", "AtomicBoolean"), KModifier.PRIVATE)
                    .initializer("java.util.concurrent.atomic.AtomicBoolean(initial)").build(),
            )
            .addProperty(
                PropertySpec.builder("isValid", Boolean::class)
                    .getter(FunSpec.getterBuilder().addStatement("return _isValid.get() && (parent?.isValid ?: true)").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invalidate")
                    .addStatement("_isValid.set(false)").build(),
            )
            .addFunction(
                FunSpec.builder("tryInvalidate")
                    .returns(Boolean::class)
                    .addStatement("return _isValid.compareAndSet(true, false)").build(),
            )
            .build()

        // --- FfiHelpers ---
        val ffiHelpers = TypeSpec.objectBuilder("FfiHelpers")
            .addFunction(
                FunSpec.builder("resolveFieldSegment")
                    .addParameter("parent", MEMORY_SEGMENT)
                    .addParameter("vh", java.lang.invoke.VarHandle::class.asClassName().copy(nullable = true))
                    .addParameter("offset", Long::class)
                    .addParameter("size", Long::class)
                    .addParameter("isOwned", Boolean::class)
                    .returns(MEMORY_SEGMENT)
                    .addCode(
                        """
                        if (parent == %T.NULL) return %T.NULL
                        return if (isOwned) {
                            parent.asSlice(offset, size)
                        } else {
                            if (vh == null) return %T.NULL
                            val ptr = vh.get(parent, offset) as %T
                            if (ptr == %T.NULL) %T.NULL else ptr.reinterpret(size)
                        }
                        """.trimIndent(),
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                    )
                    .build(),
            )
            .build()

        // --- XrossAsync ---
        val xrossAsync = TypeSpec.objectBuilder("XrossAsync")
            .addFunction(
                FunSpec.builder("awaitFuture")
                    .addModifiers(KModifier.SUSPEND)
                    .addTypeVariable(TypeVariableName("T"))
                    .addParameter("futurePtr", MEMORY_SEGMENT)
                    .addParameter("pollFunc", LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = MEMORY_SEGMENT))
                    .addParameter("mapper", LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = TypeVariableName("T")))
                    .returns(TypeVariableName("T"))
                    .addCode(
                        """
                    while (true) {
                        val result = pollFunc(futurePtr)
                        if (result != %T.NULL) {
                            return mapper(result)
                        }
                        kotlinx.coroutines.delay(1)
                    }
                        """.trimIndent(),
                        MEMORY_SEGMENT,
                    )
                    .build(),
            )
            .build()

        val file = FileSpec.builder(pkg, "XrossRuntime")
            .addImport("java.util.concurrent.atomic", "AtomicBoolean")
            .addType(xrossException)
            .addType(aliveFlag)
            .addType(ffiHelpers)
            .addType(xrossAsync)
            .build()

        GeneratorUtils.writeToDisk(file, outputDir)
    }
}
