package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        val libPath = System.getProperty("xross.lib.path")
        if (libPath != null) {
            val f = File(libPath)
            if (f.exists()) {
                System.load(f.absolutePath)
                println("Native library loaded from custom path: ${f.absolutePath}")
            } else {
                println("Warning: Custom lib path not found: $libPath")
                loadFromResources(tempDir)
            }
        } else {
            loadFromResources(tempDir)
        }

        // 1. メモリリークテスト
        executeMemoryLeakTest()

        // 2. 参照と所有権テスト
        executeReferenceAndOwnershipTest()

        // 3. アトミック並行アクセステスト
        executeConcurrencyTest()
        // 4. Enumに関するテスト
        executeEnumTest()
    } catch (e: Exception) {
        println("Test failed with exception:")
        e.printStackTrace()
    } finally {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
            println("\nTemporary directory deleted.")
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

fun executeEnumTest() {
    val enum1 = XrossTestEnum.A
    val enum2 = XrossTestEnum.B(1)
    println(enum1)
    println(enum2.i)
    enum2.i = 10
    println(enum2.i)
    val myService = MyService()
    val unknownStruct = myService.unknownStruct
    val enum3 = XrossTestEnum.C(unknownStruct.clone())
    println(enum3.j)
}

fun executeMemoryLeakTest() {
    println("\n--- [1] Memory Leak & Stability Test ---")
    val iterations = (10.0).pow(7).toInt()
    val reportInterval = iterations / 10
    val service = MyService2(0)

    val startTime = System.currentTimeMillis()
    for (i in 1..iterations) {
        service.createClone().use { clone ->
            clone.`val`.update { i }
            val res = clone.execute()
            if (i % reportInterval == 0) {
                val usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                println("Iteration $i: Val=${clone.`val`}, Res=$res, JVM Mem: ${usedMem}MB")
            }
        }
    }
    val endTime = System.currentTimeMillis()
    val duration = (endTime - startTime) / 1000.0
    val throughput = iterations / duration
    println("Memory Leak Test Finished in ${duration}s (${throughput.toInt()} ops/s).")
    service.close()
}

fun executeReferenceAndOwnershipTest() {
    println("\n--- [2] Reference & Ownership Semantics Test ---")
    val parent = MyService2(100)
    val borrowed = parent.getSelfRef()

    println("Parent value: ${parent.`val`}")
    borrowed.close()

    val borrowed2 = parent.getSelfRef()
    parent.close()
    println("Parent closed.")

    try {
        borrowed2.execute()
    } catch (e: NullPointerException) {
        println("Success: Caught expected NullPointerException, $e")
    }
}

/**
 * 3. アトミック並行アクセステスト
 * VarHandle を使用した getAndAddVal の正確性を検証します。
 */
fun executeConcurrencyTest() {
    println("\n--- [3] Multi-threaded Atomic Concurrency Test ---")
    val threadCount = 10
    val opsPerThread = 1000 // 各スレッドで1000回加算
    val expectedFinalValue = threadCount * opsPerThread // 合計 10,000 になるはず

    val shared = MyService2(0)
    val executor = Executors.newFixedThreadPool(threadCount)
    val start = System.currentTimeMillis()

    repeat(threadCount) {
        executor.submit {
            repeat(opsPerThread) {
                // 生成された Atomic ヘルパーを使用
                shared.`val`.update { it + 1 }
            }
        }
    }

    executor.shutdown()
    if (executor.awaitTermination(1, TimeUnit.MINUTES)) {
        val end = System.currentTimeMillis()
        val finalVal = shared.`val`.value
        println("Concurrency test finished in ${end - start}ms")
        println("Final shared value: $finalVal (Expected: $expectedFinalValue)")

        if (finalVal == expectedFinalValue) {
            println("✅ Success: Atomic operations work perfectly!")
        } else {
            println("❌ Failure: Value mismatch. Atomic behavior not guaranteed.")
        }
    }
    shared.close()
}
