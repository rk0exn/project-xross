package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files

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
        executeTest()

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

fun executeTest() {
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
