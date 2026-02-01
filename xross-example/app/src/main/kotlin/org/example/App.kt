package org.example

import org.example.test.test2.MyService2
import java.io.File
import java.nio.file.Files

fun main() {
    // 1. 一時フォルダの作成
    val tempDir: File = Files.createTempDirectory("xross_test_").toFile()

    try {
        // 2. リソースから .so ファイルを一時フォルダにコピー
        // ファイル名は環境に合わせて調整してください (例: libxross_example.so)
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
    println("--- Starting Test Execution ---")

    // MyService のテスト
    val myService = MyService()
    myService.use { service -> // AutoCloseable なので use が使える
        val i = service.execute(2)
        println("MyService.execute(2) result: $i")
    }

    // MyService2 のテスト (コンストラクタ引数あり)
    val myService2 = MyService2(`val` = 1)
    myService2.use { service2 ->
        val i2 = service2.execute()
        println("MyService2.execute() result: $i2")
        println("MyService2 current val field: ${service2.`val`}")
    }

    println("--- Test Execution Finished ---")
}