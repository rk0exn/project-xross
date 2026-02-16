package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

/**
 * Generates the common runtime components for Xross in Kotlin.
 */
object RuntimeGenerator {
    private val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    private val CLEANABLE = ClassName("java.lang.ref.Cleaner", "Cleanable")

    private fun TypeSpec.Builder.addStringBase(memorySegment: TypeName): TypeSpec.Builder = this.primaryConstructor(
        FunSpec.constructorBuilder()
            .addParameter("segment", memorySegment)
            .build(),
    )
        .addProperty(PropertySpec.builder("segment", memorySegment).initializer("segment").build())
        .addProperty(
            PropertySpec.builder("ptr", memorySegment)
                .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.ADDRESS, 0L)").build())
                .build(),
        )
        .addProperty(
            PropertySpec.builder("len", Long::class)
                .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_LONG, 8L)").build())
                .build(),
        )

    fun generate(outputDir: File, basePackage: String) {
        val pkg = "$basePackage.xross.runtime"

        // --- XrossException ---
        val xrossException = TypeSpec.classBuilder("XrossException")
            .superclass(Throwable::class)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("error", Any::class).build())
            .addProperty(PropertySpec.builder("error", Any::class).initializer("error").build())
            .build()

        // --- AliveFlag ---
        val aliveFlag = TypeSpec.classBuilder("AliveFlag")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("initial", Boolean::class)
                    .addParameter(
                        ParameterSpec.builder("parent", ClassName(pkg, "AliveFlag").copy(nullable = true))
                            .defaultValue("null").build(),
                    )
                    .addParameter(ParameterSpec.builder("isPersistent", Boolean::class).defaultValue("false").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "parent",
                    ClassName(pkg, "AliveFlag").copy(nullable = true),
                    KModifier.PRIVATE,
                ).initializer("parent").build(),
            )
            .addProperty(
                PropertySpec.builder("isPersistent", Boolean::class, KModifier.INTERNAL).initializer("isPersistent")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "_isValid",
                    ClassName("java.util.concurrent.atomic", "AtomicBoolean"),
                    KModifier.PRIVATE,
                ).initializer("java.util.concurrent.atomic.AtomicBoolean(initial)").build(),
            )
            .addProperty(
                PropertySpec.builder("isValid", Boolean::class)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return (isPersistent || _isValid.get()) && (parent?.isValid ?: true)")
                            .build(),
                    )
                    .build(),
            )
            .addFunction(FunSpec.builder("invalidate").addStatement("if (!isPersistent) _isValid.set(false)").build())
            .addFunction(
                FunSpec.builder("tryInvalidate")
                    .returns(Boolean::class)
                    .addCode("if (isPersistent) return false\nreturn _isValid.compareAndSet(true, false)\n")
                    .build(),
            )
            .build()

        // --- XrossObject Interface ---
        val xrossObject = TypeSpec.interfaceBuilder("XrossObject")
            .addSuperinterface(AutoCloseable::class)
            .addProperty(PropertySpec.builder("segment", MEMORY_SEGMENT).build())
            .addProperty(PropertySpec.builder("aliveFlag", ClassName(pkg, "AliveFlag")).build())
            .addFunction(FunSpec.builder("relinquish").build())
            .build()

        // --- XrossNativeObject Base Class ---
        val xrossNativeObject = TypeSpec.classBuilder("XrossNativeObject")
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(ClassName(pkg, "XrossObject"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addParameter("arena", Arena::class)
                    .addParameter("aliveFlag", ClassName(pkg, "AliveFlag"))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.OVERRIDE).initializer("segment").build(),
            )
            .addProperty(PropertySpec.builder("arena", Arena::class, KModifier.INTERNAL).initializer("arena").build())
            .addProperty(
                PropertySpec.builder("aliveFlag", ClassName(pkg, "AliveFlag"), KModifier.OVERRIDE)
                    .initializer("aliveFlag").build(),
            )
            .addProperty(
                PropertySpec.builder("cleanable", CLEANABLE.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("if (aliveFlag.isPersistent) null else %T.registerCleaner(this, arena, aliveFlag)", ClassName(pkg, "XrossRuntime")).build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addStatement("cleanable?.clean()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("relinquish")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addStatement("aliveFlag.invalidate()")
                    .build(),
            )
            .build()

        // --- XrossRuntime ---
        val xrossRuntime = TypeSpec.objectBuilder("XrossRuntime")
            .addProperty(
                PropertySpec.builder("CLEANER", ClassName("java.lang.ref", "Cleaner"), KModifier.PRIVATE)
                    .initializer("java.lang.ref.Cleaner.create()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("STRING_VALUE_FIELD", java.lang.reflect.Field::class.asTypeName().copy(nullable = true), KModifier.PRIVATE)
                    .initializer("runCatching { String::class.java.getDeclaredField(\"value\").apply { isAccessible = true } }.getOrNull()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("STRING_CODER_FIELD", java.lang.reflect.Field::class.asTypeName().copy(nullable = true), KModifier.PRIVATE)
                    .initializer("runCatching { String::class.java.getDeclaredField(\"coder\").apply { isAccessible = true } }.getOrNull()")
                    .build(),
            )
            .addInitializerBlock(
                CodeBlock.builder()
                    .add("// --- Xross Runtime Initialization ---\n")
                    .beginControlFlow("try")
                    .addStatement("val runtime = Runtime.getRuntime()")
                    .addStatement("val initialHeap = runtime.totalMemory()")
                    .addStatement("val dedicatedSize = initialHeap / 2")
                    .addStatement("val arena = %T.global()", Arena::class.asTypeName())
                    .addStatement("val heap = arena.allocate(dedicatedSize, 4096)")
                    .addStatement("val linker = %T.nativeLinker()", java.lang.foreign.Linker::class.asTypeName())
                    .addStatement("val lookup = %T.loaderLookup()", java.lang.foreign.SymbolLookup::class.asTypeName())
                    .addStatement("val initSym = lookup.find(\"xross_runtime_init\")")
                    .beginControlFlow("if (initSym.isPresent)")
                    .addStatement("val initHandle = linker.downcallHandle(initSym.get(), %T.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))", java.lang.foreign.FunctionDescriptor::class.asTypeName())
                    .addStatement("initHandle.invokeExact(heap as %T, dedicatedSize)", MEMORY_SEGMENT)
                    .endControlFlow()
                    .nextControlFlow("catch (e: %T)", Throwable::class.asTypeName())
                    .addStatement("System.err.println(\"[Xross] Failed to initialize runtime: \" + e.message)")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("ofSmart")
                    .returns(Arena::class)
                    .addKdoc("Returns an Arena that can be safely closed by a Cleaner thread.")
                    .addCode("return %T.ofShared()", Arena::class)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerCleaner")
                    .addParameter("target", Any::class)
                    .addParameter("arena", Arena::class)
                    .addParameter("flag", ClassName(pkg, "AliveFlag"))
                    .returns(CLEANABLE)
                    .addCode(
                        """
                        return CLEANER.register(target) {
                            if (flag.tryInvalidate()) {
                                try { arena.close() } catch (e: Throwable) {}
                            }
                        }
                        """.trimIndent(),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringValue")
                    .addParameter("s", String::class)
                    .returns(ByteArray::class.asTypeName().copy(nullable = true))
                    .addCode("return STRING_VALUE_FIELD?.get(s) as? ByteArray")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringCoder")
                    .addParameter("s", String::class)
                    .returns(Byte::class)
                    .addCode("return STRING_CODER_FIELD?.get(s) as? Byte ?: 0.toByte()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("createStringView")
                    .addParameter("s", String::class)
                    .addParameter("arena", Arena::class)
                    .returns(MEMORY_SEGMENT)
                    .addKdoc("Deprecated: Use getStringValue/Coder for zero-copy.")
                    .addCode(
                        """
                        val view = arena.allocate(24) // XrossStringView size
                        val value = getStringValue(s)
                        val coder = getStringCoder(s)

                        if (value != null) {
                            val buf = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *value)
                            view.set(java.lang.foreign.ValueLayout.ADDRESS, 0L, buf)
                            view.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8L, s.length.toLong())
                            view.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16L, coder)
                        } else {
                            val buf = arena.allocateFrom(s)
                            view.set(java.lang.foreign.ValueLayout.ADDRESS, 0L, buf)
                            view.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8L, buf.byteSize())
                            view.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 16L, 0.toByte())
                        }
                        return view
                        """.trimIndent(),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeDrop")
                    .addParameter("handle", MethodHandle::class)
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addCode(
                        """
                        try {
                            if (handle.type().returnType() == java.lang.Void.TYPE && handle.type().parameterCount() == 2) {
                                java.lang.foreign.Arena.ofConfined().use { arena ->
                                    val outPanic = arena.allocate(16) // XrossResult size
                                    handle.invoke(outPanic, segment)
                                    val isOk = outPanic.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()
                                    if (!isOk) {
                                        val ptr = outPanic.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)
                                        val msg = if (ptr == %T.NULL) "Unknown" else %T(ptr.reinterpret(24)).toString()
                                        System.err.println("[Xross] Panic during drop: " + msg)
                                    }
                                }
                            } else if (handle.type().returnType() == %T::class.java) {
                                // Fallback for old layout-based direct return if any
                                java.lang.foreign.Arena.ofConfined().use { arena ->
                                    val resRaw = handle.invoke(arena as java.lang.foreign.SegmentAllocator, segment) as %T
                                    val isOk = resRaw.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()
                                    if (!isOk) {
                                        val ptr = resRaw.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)
                                        val msg = if (ptr == %T.NULL) "Unknown" else %T(ptr.reinterpret(24)).toString()
                                        System.err.println("[Xross] Panic during drop: " + msg)
                                    }
                                }
                            } else {
                                handle.invoke(segment)
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                        """.trimIndent(),
                        MEMORY_SEGMENT,
                        ClassName(pkg, "XrossString"),
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        MEMORY_SEGMENT,
                        ClassName(pkg, "XrossString"),
                    )
                    .build(),
            )
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
                    .addParameter("taskPtr", MEMORY_SEGMENT)
                    .addParameter("pollFn", MethodHandle::class)
                    .addParameter("dropFn", MethodHandle::class)
                    .addParameter(
                        "mapper",
                        LambdaTypeName.get(null, MEMORY_SEGMENT, returnType = TypeVariableName("T")),
                    )
                    .returns(TypeVariableName("T"))
                    .addCode(
                        """
                    try {
                        java.lang.foreign.Arena.ofConfined().use { arena ->
                            while (true) {
                                val resultRaw = pollFn.invokeExact(arena as java.lang.foreign.SegmentAllocator, taskPtr) as MemorySegment
                                val isOk = resultRaw.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()
                                val ptr = resultRaw.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)

                                if (ptr != MemorySegment.NULL) {
                                    if (!isOk) {
                                        val errXs = XrossString(ptr.reinterpret(24))
                                        throw XrossException(errXs.toString())
                                    }
                                    return mapper(ptr)
                                }
                                kotlinx.coroutines.delay(1)
                            }
                        }
                    } finally {
                        dropFn.invoke(taskPtr)
                    }
                        """.trimIndent(),
                    )
                    .build(),
            )
            .build()

        // --- XrossAsyncLock ---
        val xrossAsyncLock = TypeSpec.classBuilder("XrossAsyncLock")
            .addProperty(
                PropertySpec.builder("rw", ClassName("java.util.concurrent.locks", "ReentrantReadWriteLock"))
                    .initializer("java.util.concurrent.locks.ReentrantReadWriteLock(true)")
                    .addModifiers(KModifier.PRIVATE).build(),
            )
            .addFunction(
                FunSpec.builder("lockRead").addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.readLock().tryLock()) { kotlinx.coroutines.delay(1) }").build(),
            )
            .addFunction(FunSpec.builder("unlockRead").addCode("rw.readLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockWrite").addModifiers(KModifier.SUSPEND)
                    .addCode("while (!rw.writeLock().tryLock()) { kotlinx.coroutines.delay(1) }").build(),
            )
            .addFunction(FunSpec.builder("unlockWrite").addCode("rw.writeLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockReadBlocking").addCode("rw.readLock().lock()").build(),
            )
            .addFunction(FunSpec.builder("unlockReadBlocking").addCode("rw.readLock().unlock()").build())
            .addFunction(
                FunSpec.builder("lockWriteBlocking").addCode("rw.writeLock().lock()").build(),
            )
            .addFunction(FunSpec.builder("unlockWriteBlocking").addCode("rw.writeLock().unlock()").build())
            .build()

        val toStringBody = CodeBlock.builder()
            .add(
                """
                if (ptr == %T.NULL || len == 0L) return ""
                val bytes = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE)
                return String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                """.trimIndent(),
                MEMORY_SEGMENT,
            ).build()

        // --- XrossString ---
        val xrossString = TypeSpec.classBuilder("XrossString")
            .addStringBase(MEMORY_SEGMENT)
            .addProperty(
                PropertySpec.builder("cap", Long::class)
                    .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_LONG, 16L)").build())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toString")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(String::class)
                    .addCode(toStringBody)
                    .build(),
            )
            .build()

        // --- XrossStringView ---
        val xrossStringView = TypeSpec.classBuilder("XrossStringView")
            .addStringBase(MEMORY_SEGMENT)
            .addProperty(
                PropertySpec.builder("encoding", Byte::class)
                    .getter(FunSpec.getterBuilder().addStatement("return segment.get(ValueLayout.JAVA_BYTE, 16L)").build())
                    .build(),
            )
            .build()

        val file = FileSpec.builder(pkg, "XrossRuntime")
            .addImport("java.util.concurrent.atomic", "AtomicBoolean")
            .addImport("java.util.concurrent.locks", "ReentrantReadWriteLock")
            .addImport("java.lang.foreign", "ValueLayout", "SegmentAllocator", "Arena", "Linker", "SymbolLookup", "FunctionDescriptor")
            .addType(xrossException)
            .addType(aliveFlag)
            .addType(xrossObject)
            .addType(xrossNativeObject)
            .addType(xrossRuntime)
            .addType(xrossAsync)
            .addType(xrossAsyncLock)
            .addType(xrossString)
            .addType(xrossStringView)
            .build()

        GeneratorUtils.writeToDisk(file, outputDir)
    }
}
