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

        // 1. メモリリークテスト (高負荷なため1回のみ)
        executeMemoryLeakTest()

        // 2-6. 安定性テストの繰り返し実行
        val repeatCount = 100
        println("\n--- Starting Repetitive Stability Test ($repeatCount cycles) ---")

        for (i in 1..repeatCount) {
            executeReferenceAndOwnershipTest()
            executeConcurrencyTest()
            executeEnumTest()
            executeCollectionAndOptionalTest()
            executePropertyTest()
            executeComplexFieldTest()

            if (i % 10 == 0) {
                println(">>> Completed cycle $i / $repeatCount")
            }
        }

        println("\n✅ All $repeatCount cycles finished without any crashes or memory errors!")
    } catch (e: Exception) {
        println("Test failed with exception:")
        e.printStackTrace()
    } finally {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
            println("\nTemporary directory deleted.")
        }
        println("\n--- Final Analysis before GC ---")
        println(UnknownStruct.displayAnalysis())
        // --- 強制的にGCを促して確認 ---
        System.gc()
        Thread.sleep(1000)
        println("\n--- Final Analysis after GC ---")
        println(UnknownStruct.displayAnalysis())
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
fun executeComplexFieldTest(){
    println("\nComplex field test:")

}

fun executePropertyTest() {
    val unknownStruct = UnknownStruct(1, "Hello", 1f)
    println(unknownStruct.s)
    unknownStruct.s = "Hello, World. from modified, 無限、❤"
    println(unknownStruct.s)
}

fun executeEnumTest() {
    println("\n--- [4] Enum Return & Pattern Matching Statistics ---")
    val myService = MyService()

    // 1. 基本的なフィールドアクセステスト
    val enum1 = XrossTestEnum.A()
    val enum2 = XrossTestEnum.B(1)
    println("Static Variant A: $enum1")
    println("Static Variant B value: ${enum2.i}")
    enum2.i = 10
    println("Modified Variant B value: ${enum2.i}")

    val unknownStruct = myService.unknownStruct
    val enum3 = XrossTestEnum.C(unknownStruct.clone())
    println("Static Variant C value (i): ${enum3.j}")

    // 2. 統計取得
    val iterations = 100000
    var countA = 0
    var countB = 0
    var countC = 0

    val startTime = System.currentTimeMillis()
    repeat(iterations) {
        // equals実装により A は定数として比較可能、B/C は型チェック(is)で分岐
        myService.retEnum().use {
            when (it) {
                is XrossTestEnum.A -> countA++
                is XrossTestEnum.B -> countB++
                is XrossTestEnum.C -> countC++
            }
        }
    }
    val endTime = System.currentTimeMillis()

    println("Finished $iterations iterations in ${endTime - startTime}ms:")
    println("  - Variant A: $countA times")
    println("  - Variant B: $countB times")
    println("  - Variant C: $countC times")

    myService.close()
    val simpleEnum = XrossSimpleEnum.X
    simpleEnum.sayHello()
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

    println("Parent value: ${parent.`val`.value}")
    borrowed.close()
    println("Borrowed reference closed.")

    // 参照を閉めても親は生きているはず
    try {
        println("Checking parent after borrowed.close(): ${parent.execute()}")
        println("✅ Success: Parent is still alive after closing a borrowed reference.")
    } catch (e: NullPointerException) {
        println("❌ Failure: Parent was incorrectly invalidated by closing a borrowed reference!")
        println("Reason: ${e.message}")
    }

    val borrowed2 = parent.getSelfRef()
    parent.close()
    println("Parent closed.")

    // 親を閉めたら、そこから派生した参照も死ぬはず
    try {
        borrowed2.execute()
        println("❌ Failure: Borrowed reference should have been invalidated by closing the parent!")
    } catch (e: NullPointerException) {
        println("✅ Success: Caught expected NullPointerException for borrowed2 after parent.close(), $e")
    }

    println("\n--- [2.1] Consumption (self) Test ---")
    val serviceToConsume = MyService()
    val len = serviceToConsume.consumeSelf()
    println("Service consumed, len: $len")
    try {
        serviceToConsume.execute(10)
        println("❌ Failure: Service should have been invalidated!")
    } catch (_: NullPointerException) {
        println("✅ Success: Caught expected NullPointerException for consumed service.")
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

fun executeCollectionAndOptionalTest() {
    println("\n--- [5] Optional Types Test ---")
    val service = MyService()
    // 1. Option Test
    val someEnum = service.getOptionEnum(true)
    println("Option(true) result: $someEnum")
    val noneEnum = service.getOptionEnum(false)
    println("Option(false) result: $noneEnum")

    // 2. Result Test
    val okResult = service.getResultStruct(true)
    println("Result(true) isSuccess: ${okResult.isSuccess}, value: ${okResult.getOrNull()?.`val`?.value}")

    val errResult = service.getResultStruct(false)
    println("Result(false) isFailure: ${errResult.isFailure}, exception: ${errResult.exceptionOrNull()}")
    if (errResult.isFailure) {
        val ex = errResult.exceptionOrNull() as? org.example.xross.runtime.XrossException
        println("  -> Inner error: ${ex?.error}")
    }

    service.close()
}
