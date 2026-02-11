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
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("error", Any::class)
                .build())
            .addProperty(PropertySpec.builder("error", Any::class).initializer("error").build())
            .build()

        // --- AliveFlag ---
        val aliveFlag = TypeSpec.classBuilder("AliveFlag")
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("initial", Boolean::class)
                .build())
            .addProperty(PropertySpec.builder("isValid", Boolean::class).mutable(true).initializer("initial").build())
            .build()

        // --- XrossAsync ---
        val xrossAsync = TypeSpec.objectBuilder("XrossAsync")
            .addFunction(FunSpec.builder("awaitFuture")
                .addModifiers(KModifier.SUSPEND)
                .addTypeVariable(TypeVariableName("T"))
                .addParameter("futurePtr", MEMORY_SEGMENT)
                .addParameter("pollFunc", LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = MEMORY_SEGMENT))
                .addParameter("mapper", LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = TypeVariableName("T")))
                .returns(TypeVariableName("T"))
                .addCode("""
                    while (true) {
                        val result = pollFunc(futurePtr)
                        if (result != %T.NULL) {
                            return mapper(result)
                        }
                        kotlinx.coroutines.delay(1)
                    }
                """.trimIndent(), MEMORY_SEGMENT)
                .build())
            .build()

        val file = FileSpec.builder(pkg, "XrossRuntime")
            .addType(xrossException)
            .addType(aliveFlag)
            .addType(xrossAsync)
            .build()
        
        val content = XrossGenerator.cleanupPublic(file.toString())
        val fileDir = outputDir.resolve(pkg.replace('.', '/'))
        if (!fileDir.exists()) fileDir.mkdirs()
        fileDir.resolve("XrossRuntime.kt").writeText(content)
    }
}
