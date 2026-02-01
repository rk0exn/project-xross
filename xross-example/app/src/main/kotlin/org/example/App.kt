package org.example

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
    println("--- Starting Memory Leak Test ---")

    val myService = MyService()
    myService.use { service ->
        // 100万回実行してメモリが増え続けないか確認
        val iterations = 1_000_000_000
        val reportInterval = 100_000_000

        println("Running str_test() $iterations times...")

        for (i in 1..iterations) {
            val s = service.str_test()

            // 文字列が正しく取得できているか時々チェック
            if (i % reportInterval == 0) {
                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                println("Iteration $i: String sample = '$s', JVM Used Memory: ${mem / 1024 / 1024} MB")
            }
        }
    }

    println("--- Memory Leak Test Finished ---")
    println("Check your OS process monitor (Task Manager / top) to see if 'RSS' is stable.")
}
