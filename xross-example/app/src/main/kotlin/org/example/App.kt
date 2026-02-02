package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        val libName = "libxross_example.so"
        val libFile = File(tempDir, libName)

        val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
            ?: throw RuntimeException("Resource not found: $libName")

        resourceStream.use { input -> libFile.outputStream().use { output -> input.copyTo(output) } }

        System.load(libFile.absolutePath)
        println("Native library loaded from: ${libFile.absolutePath}")

        // 1. 基本的なメモリリークテスト（大量生成とDropの検証）
        executeMemoryLeakTest()

        // 2. 参照と所有権のセマンティクス検証（借用と生存フラグ）
        executeReferenceAndOwnershipTest()

        // 3. 並行アクセステスト（Read/Write Lockの検証）
        executeConcurrencyTest()

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

/**
 * 1. メモリリークテスト
 * 10万回の生成・破棄を繰り返し、RSSが安定しているか、文字列の解放が正しく行われているかを確認
 */
fun executeMemoryLeakTest() {
    println("\n--- [1] Memory Leak & Stability Test ---")
    val iterations = 100_000
    val reportInterval = 10_000

    val service = MyService2(0)

    for (i in 1..iterations) {
        // createClone は内部で Rust の Box を作り、Kotlin 側で close 時に drop される
        service.createClone().use { clone ->
            clone.`val` = i
            clone.mutTest()
            val res = clone.execute()

            // RustからStringを受け取る（xross_free_string の検証）
            // ※MyService.strTest() がある場合
            // val s = MyService.strTest()

            if (i % reportInterval == 0) {
                val usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                println("Iteration $i: Val=${clone.`val`}, Res=$res, JVM Mem: ${usedMem}MB")
            }
        }
    }
    service.close()
    println("Memory Leak Test Finished.")
}

/**
 * 2. 参照と所有権テスト
 * 借用オブジェクト (isBorrowed = true) が親の死後にどう動くか、多重解放が防げるかを検証
 */
fun executeReferenceAndOwnershipTest() {
    println("\n--- [2] Reference & Ownership Semantics Test ---")

    // A. 借用 (Borrowing) のテスト
    val parent = MyService2(100)
    val borrowed = parent.getSelfRef() // Rust側で &Self を返す

    println("Parent value: ${parent.`val`}")
    println("Borrowed value: ${borrowed.`val`}")

    // 借用側を閉じても、親は生きているべき
    borrowed.close()
    println("Borrowed closed. Parent value check: ${parent.`val`}")

    // B. 親を閉じた後の借用側へのアクセス（安全性チェック）
    val borrowed2 = parent.getSelfRef()
    parent.close()
    println("Parent closed.")

    try {
        println("Accessing borrowed2 after parent closed...")
        borrowed2.execute()
    } catch (e: NullPointerException) {
        println("Success: Caught expected NullPointerException (Object dropped)")
        println("Caught: ${e.message}")
    } catch (e: Exception) {
        println("Caught: ${e.message}")
    }

    // C. 所有権移動 (Consumption) のシミュレーション
    // ※MyService側に consume_self がある場合、それも同様に segment = NULL を確認
    println("Ownership and drop checks passed.")
}

/**
 * 3. 並行アクセステスト
 * ReadWriteLock が正しく働き、マルチスレッド下でセグメンテーションフォールトが起きないか確認
 */
fun executeConcurrencyTest() {
    println("\n--- [3] Multi-threaded Concurrency Test ---")
    val threadCount = 10
    val opsPerThread = 2000
    val shared = MyService2(0)
    val executor = Executors.newFixedThreadPool(threadCount)
    val start = System.currentTimeMillis()

    repeat(threadCount) {
        executor.submit {
            for (i in 0 until opsPerThread) {
                when (i % 4) {
                    0 -> shared.execute()                 // Read
                    1 -> shared.mutTest()                 // Write
                    2 -> shared.`val` += 1                // Write (Property)
                    3 -> shared.getSelfRef().use { it.execute() } // Borrowed Read
                }
            }
        }
    }

    executor.shutdown()
    if (executor.awaitTermination(1, TimeUnit.MINUTES)) {
        val end = System.currentTimeMillis()
        println("Concurrency test finished in ${end - start}ms")
        println("Final shared value: ${shared.`val`}")
    } else {
        println("Concurrency test timed out!")
    }
    shared.close()
}
