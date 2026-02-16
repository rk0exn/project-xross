package org.example

import kotlinx.coroutines.runBlocking
import org.example.complex.ComplexStruct
import org.example.external.ExternalStruct
import org.example.standalone.GlobalAdd
import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        val libPath = System.getProperty("xross.lib.path")
        if (libPath != null) {
            val f = File(libPath)
            if (f.exists()) {
                System.load(f.absolutePath)
            } else {
                loadFromResources(tempDir)
            }
        } else {
            loadFromResources(tempDir)
        }

        executeMemoryLeakTest()
        println("\n--- Running Stability Test ---")
        executePrimitiveTypeTest()
        executeReferenceAndOwnershipTest()
        executeConcurrencyTest()
        executeEnumTest()
        executeCollectionAndOptionalTest()
        executePropertyTest()
        executeComplexFieldTest()
        executeComplexStructPropertyTest()
        executePanicAndTrivialTest()
        executeStandaloneFunctionTest()
        executeAsyncTest()

        println("\n✅ All tests finished!")
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }
}

private fun loadFromResources(tempDir: File) {
    val libName = "libxross_example.so"
    val libFile = File(tempDir, libName)
    val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
        ?: throw RuntimeException("Resource not found: $libName")

    resourceStream.use { input -> libFile.outputStream().use { output -> input.copyTo(output) } }
    System.load(libFile.absolutePath)
    println("Native library loaded from: ${libFile.absolutePath}")
}

// --- 統合テスト用関数群 (silentパラメータ付き) ---

fun executePanicAndTrivialTest(silent: Boolean = false) {
    val service = MyService()
    try {
        val sum = service.addTrivial(10, 20)
        if (!silent) println("Trivial add: $sum")
        service.addCriticalHeap(100, 200)
        service.causePanic(0.toByte())
    } catch (e: Throwable) {
        if (!silent) println("Caught expected or unexpected exception: $e")
    } finally {
        service.close()
    }
}

fun executeStandaloneFunctionTest(silent: Boolean = false) {
    val res = GlobalAdd.globalAdd(10, 20)
    if (!silent) println("Global add: $res")
    org.example.standalone.GlobalGreet.globalGreet("Xross")
    org.example.standalone.GlobalMultiply.globalMultiply(5, 6)
}

fun executeAsyncTest(silent: Boolean = false) = runBlocking {
    val res = org.example.standalone.AsyncAdd.asyncAdd(100, 200)
    if (!silent) println("Async add: $res")
    org.example.standalone.AsyncGreet.asyncGreet("Coroutines")
}

fun executePrimitiveTypeTest(silent: Boolean = false) {
    val pt = PrimitiveTest(5.toByte(), 500, 5000L)
    pt.addU32(500)
    if (!silent) println("Primitive u32: ${pt.u32Val}")
    pt.close()
}

fun executeComplexStructPropertyTest(silent: Boolean = false) {
    val cs = ComplexStruct(42, Result.success(100))
    cs.opt = 100
    cs.res = Result.success(500)
    if (!silent) println("ComplexStruct res: ${cs.res}")
    cs.close()
}

fun executeComplexFieldTest(silent: Boolean = false) {
    val ext = ExternalStruct(100, "Xross Native")
    ext.value = 500
    ext.getValue()
    val greet = ext.greet("Hello")
    if (!silent) println("Greet: $greet")
    ext.close()
}

fun executePropertyTest(silent: Boolean = false) {
    val unknownStruct = UnknownStruct(1, "Hello", 1f)
    unknownStruct.s = "Modified"
    if (!silent) println("Struct string: ${unknownStruct.s}")
    unknownStruct.close()
}

fun executeEnumTest(silent: Boolean = false) {
    val myService = MyService()
    val e = myService.retEnum()
    if (!silent) println("Enum result: $e")
    e.close()
    myService.close()
}

fun executeMemoryLeakTest(silent: Boolean = false) {
    val iterations = if (silent) 1000 else 10000
    val service = MyService2(0)
    for (i in 1..iterations) {
        service.createClone().use { clone ->
            clone.`val`.update { i }
            clone.execute()
        }
    }
    service.close()
}

fun executeReferenceAndOwnershipTest(silent: Boolean = false) {
    val parent = MyService2(100)
    val borrowed = parent.getSelfRef()
    if (!silent) println("Reference value: ${borrowed.`val`.value}")
    parent.close()
}

fun executeConcurrencyTest(silent: Boolean = false) {
    val shared = MyService2(0)
    shared.`val`.update { it + 1 }
    if (!silent) println("Concurrency value: ${shared.`val`.value}")
    shared.close()
}

fun executeCollectionAndOptionalTest(silent: Boolean = false) {
    val service = MyService()
    val opt = service.getOptionEnum(true)
    if (!silent) println("Option: $opt")
    service.getResultStruct(true).getOrNull()?.close()
    service.close()
}
