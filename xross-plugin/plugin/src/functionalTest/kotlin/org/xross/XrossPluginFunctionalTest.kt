package org.xross

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class XrossPluginFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }

    @Test fun `can run generateXrossBindings task`() {
        // Rustプロジェクトのダミーディレクトリを作成
        val rustProjectDir = projectDir.resolve("rust-lib")
        rustProjectDir.mkdirs()

        // メタデータJSONのダミーを配置 (target/xross/MyStruct.json)
        val metaDir = rustProjectDir.resolve("target/xross")
        metaDir.mkdirs()
        metaDir.resolve("MyStruct.json").writeText(
            """
            {
              "kind": "struct",
              "signature": "org.xross.generated.MyStruct",
              "symbolPrefix": "lib_mystruct",
              "packageName": "",
              "name": "MyStruct",
              "fields": [],
              "methods": [
                {
                  "name": "hello",
                  "symbol": "lib_hello",
                  "methodType": "Static",
                  "handleMode": { "kind": "normal" },
                  "isConstructor": false,
                  "args": [
                    {
                      "name": "value",
                      "ty": "I32",
                      "safety": "Lock",
                      "docs": []
                    }
                  ],
                  "ret": "Void",
                  "safety": "Lock",
                  "docs": ["Test comment"]
                },
                {
                  "name": "fastMethod",
                  "symbol": "lib_fast",
                  "methodType": "Static",
                  "handleMode": { "kind": "critical", "allowHeapAccess": true },
                  "isConstructor": false,
                  "args": [],
                  "ret": "I32",
                  "safety": "Lock",
                  "docs": []
                }
              ],
              "docs": []
            }
            """.trimIndent(),
        )

        // セットアップ
        settingsFile.writeText("rootProject.name = \"xross-test\"")
        buildFile.writeText(
            """
            plugins {
                id("org.xross")
            }

            xross {
                rustProjectDir = "rust-lib"
                packageName = "org.xross.generated"
            }
            """.trimIndent(),
        )

        // タスクの実行
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("generateXrossBindings")
        runner.withProjectDir(projectDir)
        val result = runner.build()

        // 1. タスクが成功したか
        assertTrue(result.output.contains("SUCCESS"), "Task should complete successfully")

        // 2. Kotlinソースコードが生成されたか
        val generatedFile = projectDir.resolve("build/generated/source/xross/main/kotlin/org/xross/generated/MyStruct.kt")
        assertTrue(generatedFile.exists(), "Generated Kotlin file should exist at $generatedFile")

        // 3. 生成されたファイルに意図したシンボルが含まれているか
        val content = generatedFile.readText()
        assertTrue(content.contains("class MyStruct"), "Generated Kotlin file should contain the MyStruct class")
        assertTrue(content.contains("fun hello"), "Generated Kotlin file should contain the generated method 'hello'")
        assertTrue(content.contains("critical(true)"), "Generated Kotlin file should contain critical(true) option")
    }
}
