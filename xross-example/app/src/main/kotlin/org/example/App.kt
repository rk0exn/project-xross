package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        val libName = "libxross_example.so"
        val libFile = File(tempDir, libName)

        val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream(libName)
            ?: throw RuntimeException("Resource not found: $libName")

        resourceStream.use { input ->
            libFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // 3. ライブラリのロード (絶対パスで指定)
        System.load(libFile.absolutePath)
        println("Native library loaded from: ${libFile.absolutePath}")

        // 4. 実際の処理を実行
        executeMemoryLeakTest()
        executeConcurrencyTest()
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        // 5. 終了時に必ず一時フォルダを削除
        if (tempDir.exists()) {
            val success = tempDir.deleteRecursively()
            println("Temporary directory deleted: $success")
        }
    }
}

fun executeMemoryLeakTest() {
    println("--- Starting mutTest() Memory Leak Test ---")

    val myService2 = MyService2(0)

    // 1億回など、大きな回数でもRSSが安定するかチェック
    val iterations = 1_000_000
    val reportInterval = iterations / 10

    println("Running mutTest() and clone() $iterations times...")

    for (i in 1..iterations) {
        // 1. 生成した瞬間に use で保護する（これが最強のリーク対策）
        myService2.clone().use { clone ->

            // 2. フィールドに直接アクセスして値をセット
            clone.`val` = i

            // 3. mutTest を実行（内部で Lock がかかる）
            clone.mutTest()

            // 4. 結果の確認（必要に応じて）
            val result = clone.execute()
            MyService.strTest()
            if (i % reportInterval == 0) {
                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                println("Iteration $i: Val = ${clone.`val`}, Result = $result, JVM Memory: ${mem / 1024 / 1024} MB")
            }
        } // ここで確実に Rust 側の drop が呼ばれる
    }

    // 最後に親オブジェクトも閉じるなら
    myService2.close()
    println("--- Test Finished. Check RSS stability. ---")
}

fun executeConcurrencyTest() {
    println("--- Starting Multi-threaded Concurrency Test ---")

    // 共有インスタンスを1つ作成
    val sharedService = MyService2(0)
    val threadCount = 8
    val executor = Executors.newFixedThreadPool(threadCount)

    val iterationsPerThread = 10_000
    val successCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)

    println("Running test with $threadCount threads, each performing $iterationsPerThread ops...")

    val start = System.currentTimeMillis()

    repeat(threadCount) { threadId ->
        executor.submit {
            try {
                for (i in 1..iterationsPerThread) {
                    // 乱数的な挙動をシミュレートするために、スレッドごとに異なる操作を混ぜる
                    when ((i + threadId) % 4) {
                        0 -> {
                            // 読み取り操作 (Read Lock)
                            sharedService.execute()
                        }

                        1 -> {
                            // 書き込み操作 (Write Lock)
                            sharedService.mutTest()
                        }

                        2 -> {
                            // フィールドへの書き込み (Write Lock)
                            sharedService.`val` += 1
                        }

                        3 -> {
                            // クローン作成 (Read Lock) と所有権移動
                            sharedService.clone().use {
                                it.execute()
                            }
                        }
                    }
                    successCount.incrementAndGet()
                }
            } catch (e: Throwable) {
                println("Thread $threadId failed: ${e.message}")
                errorCount.incrementAndGet()
            }
        }
    }

    executor.shutdown()
    executor.awaitTermination(1, TimeUnit.MINUTES)

    val end = System.currentTimeMillis()

    println("--- Test Results ---")
    println("Execution Time: ${end - start} ms")
    println("Total Successful Operations: ${successCount.get()}")
    println("Total Errors: ${errorCount.get()}")
    println("Final Value: ${sharedService.`val`}")

    sharedService.close()

    if (errorCount.get() == 0) {
        println("SUCCESS: No memory corruption or race conditions detected.")
    } else {
        println("FAILURE: Errors occurred during concurrency test.")
    }
}
