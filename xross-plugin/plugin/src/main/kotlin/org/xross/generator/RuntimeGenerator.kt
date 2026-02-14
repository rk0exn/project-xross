package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import java.io.File
import java.lang.foreign.MemorySegment

/**
 * Generates the common runtime components for Xross in Kotlin.
 */
object RuntimeGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()

    /**
     * Generates the XrossRuntime.kt file containing core exceptions and helpers.
     */
    fun generate(outputDir: File, basePackage: String) {
        val pkg = "$basePackage.xross.runtime"

        // --- XrossException ---
        val xrossException = TypeSpec.classBuilder("XrossException")
            .addKdoc("Exception thrown by Xross during bridged operations.")
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
            .addKdoc("A flag used to track the validity of a bridged object and prevent use-after-free.")
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
                    .addKdoc("Returns true if the object is still valid and has not been dropped.")
                    .getter(FunSpec.getterBuilder().addStatement("return _isValid.get() && (parent?.isValid ?: true)").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invalidate")
                    .addKdoc("Invalidates the flag.")
                    .addStatement("_isValid.set(false)").build(),
            )
            .addFunction(
                FunSpec.builder("tryInvalidate")
                    .addKdoc("Tries to invalidate the flag. Returns true if it was valid before this call.")
                    .returns(Boolean::class)
                    .addStatement("return _isValid.compareAndSet(true, false)").build(),
            )
            .build()

        // --- FfiHelpers ---
        val ffiHelpers = TypeSpec.objectBuilder("FfiHelpers")
            .addKdoc("Internal helpers for FFI operations.")
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
            .addKdoc("Helpers for asynchronous operations bridging Rust Futures to Kotlin Suspend functions.")
            .addFunction(
                FunSpec.builder("awaitFuture")
                    .addModifiers(KModifier.SUSPEND)
                    .addTypeVariable(TypeVariableName("T"))
                    .addParameter("taskPtr", MEMORY_SEGMENT)
                    .addParameter("pollFn", ClassName("java.lang.invoke", "MethodHandle"))
                    .addParameter("dropFn", ClassName("java.lang.invoke", "MethodHandle"))
                    .addParameter("mapper", LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = TypeVariableName("T")))
                    .returns(TypeVariableName("T"))
                    .addCode(
                        """
                    try {
                        while (true) {
                            java.lang.foreign.Arena.ofConfined().use { arena ->
                                val resultRaw = pollFn.invokeExact(arena as java.lang.foreign.SegmentAllocator, taskPtr) as MemorySegment
                                val isOk = resultRaw.get(ValueLayout.JAVA_BYTE, 0L) != (0).toByte()
                                val ptr = resultRaw.get(ValueLayout.ADDRESS, 8L)

                                if (ptr != MemorySegment.NULL) {
                                    if (!isOk) throw XrossException(ptr.reinterpret(Long.MAX_VALUE).getString(0))
                                    return mapper(ptr)
                                }
                            }
                            kotlinx.coroutines.delay(1)
                        }
                    } finally {
                        dropFn.invokeExact(taskPtr)
                    }
                        """.trimIndent(),
                    )
                    .build(),
            )
            .build()

        // --- XrossAsyncLock ---
        val xrossAsyncLock = TypeSpec.classBuilder("XrossAsyncLock")
            .addKdoc("A hybrid Read/Write lock supporting both coroutine suspension and thread blocking to enforce Rust ownership rules.")
            .addProperty(PropertySpec.builder("rw", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock")).initializer("java.util.concurrent.locks.ReentrantReadWriteLock(true)").addModifiers(KModifier.PRIVATE).build())
            .addFunction(
                FunSpec.builder("lockRead")
                    .addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.readLock().tryLock()) { kotlinx.coroutines.delay(1) }")
                    .build(),
            )
            .addFunction(FunSpec.builder("unlockRead").addCode("rw.readLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockWrite")
                    .addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.writeLock().tryLock()) { kotlinx.coroutines.delay(1) }")
                    .build(),
            )
            .addFunction(FunSpec.builder("unlockWrite").addCode("rw.writeLock().unlock()").build())
            .addFunction(FunSpec.builder("lockReadBlocking").addCode("rw.readLock().lock()").build())
            .addFunction(FunSpec.builder("unlockReadBlocking").addCode("rw.readLock().unlock()").build())
            .addFunction(FunSpec.builder("lockWriteBlocking").addCode("rw.writeLock().lock()").build())
            .addFunction(FunSpec.builder("unlockWriteBlocking").addCode("rw.writeLock().unlock()").build())
            .build()

        val file = FileSpec.builder(pkg, "XrossRuntime")
            .addImport("java.util.concurrent.atomic", "AtomicBoolean")
            .addImport("java.util.concurrent.locks", "ReentrantReadWriteLock")
            .addImport("java.lang.foreign", "ValueLayout")
            .addImport("kotlinx.coroutines.sync", "withLock")
            .addType(xrossException)
            .addType(aliveFlag)
            .addType(ffiHelpers)
            .addType(xrossAsync)
            .addType(xrossAsyncLock)
            .build()

        GeneratorUtils.writeToDisk(file, outputDir)
    }
}
