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
    println("--- Starting Memory Leak Test ---")

    val myService = MyService()
    myService.use { service ->
        // 100万回実行してメモリが増え続けないか確認
        val iterations = 1_000_000_00
        val reportInterval = 100_000_0

        println("Running str_test() $iterations times...")

        for (i in 1..iterations) {
            val s = MyService.strTest()
            val x = service.execute(i)
            // 文字列が正しく取得できているか時々チェック
            if (i % reportInterval == 0) {
                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                println("Iteration $i: String sample = '$s, x = $x', JVM Used Memory: ${mem / 1024 / 1024} MB")
            }
        }
    }
    val myService20 = MyService2(2)
    myService20.`val` = 3
    val myService21 = myService20.clone()
    myService21.`val` = 4
    val myService22 = myService20.clone()
    myService22.`val` = 5
    println("MyService20: ${myService20.`val`}")
    println("MyService21: ${myService21.`val`}")
    println("MyService22: ${myService22.`val`}")
    println("--- Memory Leak Test Finished ---")
    println("Check your OS process monitor (Task Manager / top) to see if 'RSS' is stable.")
}
