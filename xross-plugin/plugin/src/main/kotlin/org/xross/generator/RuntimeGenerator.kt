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
        val pkg = if (basePackage.isEmpty()) "xross.runtime" else "$basePackage.xross.runtime"

        // --- XrossException ---
        val xrossException = TypeSpec.classBuilder("XrossException")
            .superclass(Throwable::class)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("error", Any::class).build())
            .addProperty(PropertySpec.builder("error", Any::class).initializer("error").build())
            .build()

        // --- XrossObject Interface ---
        val xrossObject = TypeSpec.interfaceBuilder("XrossObject")
            .addSuperinterface(AutoCloseable::class)
            .addProperty(PropertySpec.builder("segment", MEMORY_SEGMENT).build())
            .addProperty(PropertySpec.builder("isValid", Boolean::class).build())
            .addFunction(FunSpec.builder("relinquish").build())
            .addFunction(FunSpec.builder("clearCache").build())
            .build()

        // --- XrossNativeObject Base Class ---
        val xrossNativeObject = TypeSpec.classBuilder("XrossNativeObject")
            .addModifiers(KModifier.ABSTRACT)
            .addSuperinterface(ClassName(pkg, "XrossObject"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addParameter(ParameterSpec.builder("parent", ClassName(pkg, "XrossObject").copy(nullable = true)).defaultValue("null").build())
                    .addParameter(ParameterSpec.builder("isPersistent", Boolean::class).defaultValue("false").build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("segment", MEMORY_SEGMENT, KModifier.OVERRIDE).initializer("segment").build(),
            )
            .addProperty(
                PropertySpec.builder("parent", ClassName(pkg, "XrossObject").copy(nullable = true), KModifier.PRIVATE)
                    .initializer("parent").build(),
            )
            .addProperty(
                PropertySpec.builder("isPersistent", Boolean::class, KModifier.PRIVATE)
                    .initializer("isPersistent").build(),
            )
            .addProperty(
                PropertySpec.builder("_isValid", ClassName("java.util.concurrent.atomic", "AtomicBoolean"), KModifier.PRIVATE)
                    .initializer("java.util.concurrent.atomic.AtomicBoolean(true)").build(),
            )
            .addProperty(
                PropertySpec.builder("isValid", Boolean::class, KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return (isPersistent || _isValid.get()) && (parent?.isValid ?: true)")
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("lockState", ClassName(pkg, "XrossLockState"), KModifier.INTERNAL)
                    .delegate("lazy(LazyThreadSafetyMode.PUBLICATION) { XrossLockState() }")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("cleanable", CLEANABLE.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("null").mutable(true).build(),
            )
            .addFunction(
                FunSpec.builder("clearCache")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("// Default implementation does nothing")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerNativeCleaner")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("dropHandle", MethodHandle::class)
                    .addCode(
                        "if (isPersistent || parent != null) return\n" +
                            "val s = segment\n" +
                            "val v = _isValid\n" +
                            "this.cleanable = %T.registerCleaner(this) {\n" +
                            "    if (v.compareAndSet(true, false)) {\n" +
                            "        %T.invokeDrop(dropHandle, s)\n" +
                            "    }\n" +
                            "}",
                        ClassName(pkg, "XrossRuntime"),
                        ClassName(pkg, "XrossRuntime"),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addCode(
                        "val c = cleanable\n" +
                            "if (c != null) {\n" +
                            "    c.clean()\n" +
                            "    cleanable = null\n" +
                            "} else {\n" +
                            "    relinquish()\n" +
                            "}",
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("relinquish")
                    .addModifiers(KModifier.OVERRIDE, KModifier.FINAL)
                    .addStatement("_isValid.set(false)")
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
                PropertySpec.builder("heapInitialized", ClassName("java.util.concurrent.atomic", "AtomicBoolean"), KModifier.PRIVATE)
                    .initializer("java.util.concurrent.atomic.AtomicBoolean(false)")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("STRING_VALUE_VH", ClassName("java.lang.invoke", "VarHandle").copy(nullable = true), KModifier.PRIVATE)
                    .delegate(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) { " +
                            "try { " +
                            "val lk = java.lang.invoke.MethodHandles.privateLookupIn(String::class.java, java.lang.invoke.MethodHandles.lookup()); " +
                            "lk.findVarHandle(String::class.java, \"value\", ByteArray::class.java) " +
                            "} catch (_: Throwable) { null } }",
                    ).build(),
            )
            .addProperty(
                PropertySpec.builder("STRING_CODER_VH", ClassName("java.lang.invoke", "VarHandle").copy(nullable = true), KModifier.PRIVATE)
                    .delegate(
                        "lazy(LazyThreadSafetyMode.PUBLICATION) { " +
                            "try { " +
                            "val lk = java.lang.invoke.MethodHandles.privateLookupIn(String::class.java, java.lang.invoke.MethodHandles.lookup()); " +
                            "lk.findVarHandle(String::class.java, \"coder\", Byte::class.javaPrimitiveType!!) " +
                            "} catch (_: Throwable) { null } }",
                    ).build(),
            )
            .addFunction(
                FunSpec.builder("xross_alloc")
                    .addParameter("size", Long::class)
                    .addParameter("align", Long::class)
                    .returns(MEMORY_SEGMENT)
                    .addAnnotation(AnnotationSpec.builder(ClassName("kotlin.jvm", "JvmStatic")).build())
                    .addCode(
                        "return try { java.lang.foreign.Arena.global().allocate(size, align) } catch (e: Throwable) { %T.NULL }",
                        MEMORY_SEGMENT,
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("initializeHeap")
                    .addParameter("lookup", ClassName("java.lang.foreign", "SymbolLookup"))
                    .addParameter("linker", ClassName("java.lang.foreign", "Linker"))
                    .addCode(
                        "if (System.getProperty(\"xross.heap.initialized\") == \"true\") return\n" +
                            "synchronized(XrossRuntime::class.java) {\n" +
                            "    if (System.getProperty(\"xross.heap.initialized\") == \"true\") return\n" +
                            "    try {\n" +
                            "        val symbol = lookup.find(\"xross_alloc_init\").orElseGet { \n" +
                            "            java.lang.foreign.Linker.nativeLinker().defaultLookup().find(\"xross_alloc_init\").orElse(null) \n" +
                            "        } ?: return\n" +
                            "        \n" +
                            "        val allocMethod = XrossRuntime::class.java.getDeclaredMethod(\"xross_alloc\", Long::class.java, Long::class.java)\n" +
                            "        val allocHandle = java.lang.invoke.MethodHandles.lookup().unreflect(allocMethod)\n" +
                            "        val allocDescriptor = java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_LONG)\n" +
                            "        val upcallStub = linker.upcallStub(allocHandle, allocDescriptor, java.lang.foreign.Arena.global())\n" +
                            "        \n" +
                            "        val descriptor = java.lang.foreign.FunctionDescriptor.ofVoid(java.lang.foreign.ValueLayout.ADDRESS)\n" +
                            "        val handle = linker.downcallHandle(symbol, descriptor)\n" +
                            "        handle.invoke(upcallStub)\n" +
                            "    } catch (e: Throwable) {\n" +
                            "        System.err.println(\"[Xross] Global heap init failed: \" + e.message)\n" +
                            "        e.printStackTrace()\n" +
                            "    } finally {\n" +
                            "        System.setProperty(\"xross.heap.initialized\", \"true\")\n" +
                            "    }\n" +
                            "}\n",
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("ofSmart")
                    .returns(Arena::class)
                    .addKdoc("Returns an Arena managed by GC.")
                    .addCode("return %T.ofAuto()", Arena::class)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerCleaner")
                    .addParameter("target", Any::class)
                    .addParameter("action", Runnable::class)
                    .returns(CLEANABLE)
                    .addCode("return CLEANER.register(target, action)\n")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringValue")
                    .addParameter("s", String::class)
                    .returns(ByteArray::class.asTypeName().copy(nullable = true))
                    .addCode("return try { (STRING_VALUE_VH?.get(s) as? ByteArray) } catch (_: Throwable) { null }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getStringCoder")
                    .addParameter("s", String::class)
                    .returns(Byte::class)
                    .addCode("return try { (STRING_CODER_VH?.get(s) as? Byte) ?: 0.toByte() } catch (_: Throwable) { 0.toByte() }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeDrop")
                    .addParameter("handle", MethodHandle::class)
                    .addParameter("segment", MEMORY_SEGMENT)
                    .addCode(
                        "try {\n" +
                            "    if (handle.type().returnType() == java.lang.Void.TYPE && handle.type().parameterCount() == 2) {\n" +
                            "        java.lang.foreign.Arena.ofConfined().use { arena ->\n" +
                            "            val outPanic = arena.allocate(16)\n" +
                            "            handle.invoke(outPanic, segment)\n" +
                            "        }\n" +
                            "    } else {\n" +
                            "        handle.invoke(segment)\n" +
                            "    }\n" +
                            "} catch (e: Throwable) { e.printStackTrace() }\n",
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
                        "if (parent == %T.NULL) return %T.NULL\n" +
                            "return if (isOwned) {\n" +
                            "    parent.asSlice(offset, size)\n" +
                            "} else {\n" +
                            "    if (vh == null) return %T.NULL\n" +
                            "    val ptr = vh.get(parent, offset) as %T\n" +
                            "    if (ptr == %T.NULL) %T.NULL else ptr.reinterpret(size)\n" +
                            "}\n",
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
                        "try {\n" +
                            "    java.lang.foreign.Arena.ofConfined().use { arena ->\n" +
                            "        while (true) {\n" +
                            "            val resultRaw = pollFn.invokeExact(arena as java.lang.foreign.SegmentAllocator, taskPtr) as MemorySegment\n" +
                            "            val isOk = resultRaw.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) != (0).toByte()\n" +
                            "            val ptr = resultRaw.get(java.lang.foreign.ValueLayout.ADDRESS, 8L)\n" +
                            "            if (ptr != MemorySegment.NULL) {\n" +
                            "                if (!isOk) {\n" +
                            "                    val errXs = XrossString(ptr.reinterpret(24))\n" +
                            "                    throw XrossException(errXs.toString())\n" +
                            "                }\n" +
                            "                return mapper(ptr)\n" +
                            "            }\n" +
                            "            kotlinx.coroutines.delay(1)\n" +
                            "        }\n" +
                            "    }\n" +
                            "} finally {\n" +
                            "    dropFn.invoke(taskPtr)\n" +
                            "}\n",
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

        // --- LockState ---
        val lockState = TypeSpec.classBuilder("XrossLockState")
            .addModifiers(KModifier.INTERNAL)
            .addProperty(PropertySpec.builder("sl", ClassName("java.util.concurrent.locks", "StampedLock"), KModifier.INTERNAL).initializer("java.util.concurrent.locks.StampedLock()").build())
            .addProperty(PropertySpec.builder("fl", ClassName("java.util.concurrent.locks", "ReentrantLock"), KModifier.INTERNAL).initializer("java.util.concurrent.locks.ReentrantLock(true)").build())
            .addProperty(PropertySpec.builder("al", ClassName(pkg, "XrossAsyncLock"), KModifier.INTERNAL).initializer("XrossAsyncLock()").build())
            .build()

        val toStringBody = CodeBlock.builder()
            .add(
                "if (ptr == %T.NULL || len == 0L) return \"\"\n" +
                    "val bytes = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE)\n" +
                    "return String(bytes, java.nio.charset.StandardCharsets.UTF_8)\n",
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
            .addType(xrossObject)
            .addType(xrossNativeObject)
            .addType(xrossRuntime)
            .addType(xrossAsync)
            .addType(xrossAsyncLock)
            .addType(lockState)
            .addType(xrossString)
            .addType(xrossStringView)
            .build()

        GeneratorUtils.writeToDisk(file, outputDir)
    }
}
