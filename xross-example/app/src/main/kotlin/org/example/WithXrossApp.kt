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

        // 1. メモリリークテスト (1回のみ)
        executeMemoryLeakTest()

        // 2. 安定性テスト (確認のため1回だけ実行)
        println("\n--- Running Stability Test (1 cycle) ---")
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

// --- 統合テスト用関数群 ---

fun executePanicAndTrivialTest() {
    val service = MyService()
    service.addTrivial(10, 20)
    service.addCriticalHeap(100, 200)
    service.causePanic(false)
    service.close()
}

fun executeStandaloneFunctionTest() {
    GlobalAdd.globalAdd(10, 20)
    org.example.standalone.GlobalGreet.globalGreet("Xross")
    org.example.standalone.GlobalMultiply.globalMultiply(5, 6)
}

fun executeAsyncTest() = runBlocking {
    org.example.standalone.AsyncAdd.asyncAdd(100, 200)
    org.example.standalone.AsyncGreet.asyncGreet("Coroutines")
}

fun executePrimitiveTypeTest() {
    val pt = PrimitiveTest(5.toByte(), 500, 5000L)
    pt.addU32(500)
    pt.close()
}

fun executeComplexStructPropertyTest() {
    val cs = ComplexStruct(42, Result.success(100))
    cs.opt = 100
    cs.res = Result.success(500)
    cs.close()
}

fun executeComplexFieldTest() {
    val ext = ExternalStruct(100, "Xross Native")
    ext.value = 500
    ext.getValue()
    ext.greet("Hello")
    ext.close()
}

fun executePropertyTest() {
    val unknownStruct = UnknownStruct(1, "Hello", 1f)
    unknownStruct.s = "Modified"
    unknownStruct.close()
}

fun executeEnumTest() {
    val myService = MyService()
    myService.retEnum().close()
    myService.close()
}

fun executeMemoryLeakTest() {
    val iterations = 100000 // ベンチマークメインなので減らします
    val service = MyService2(0)
    for (i in 1..iterations) {
        service.createClone().use { clone ->
            clone.`val`.update { i }
            clone.execute()
        }
    }
    service.close()
}

fun executeReferenceAndOwnershipTest() {
    val parent = MyService2(100)
    val borrowed = parent.getSelfRef()
    // borrowed.close() // DO NOT CLOSE BORROWED REFERENCES IN XROSS
    parent.close()
}

fun executeConcurrencyTest() {
    val shared = MyService2(0)
    shared.`val`.update { it + 1 }
    shared.close()
}

fun executeCollectionAndOptionalTest() {
    val service = MyService()
    service.getOptionEnum(true)
    service.getResultStruct(true).getOrNull()?.close()
    service.close()
}
