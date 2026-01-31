package org.xross

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.Test
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

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
        metaDir.resolve("MyStruct.json").writeText("""
            {
              "package_name": "org.xross.generated",
              "struct_name": "MyStruct",
              "methods": [
                {
                  "name": "hello",
                  "symbol": "lib_hello",
                  "is_constructor": false,
                  "args": ["I32"],
                  "ret": "Void",
                  "docs": ["Test comment"]
                }
              ]
            }
        """.trimIndent())

        // セットアップ
        settingsFile.writeText("rootProject.name = \"xross-test\"")
        buildFile.writeText("""
            plugins {
                id("org.xross")
            }

            xross {
                rustProjectDir = "rust-lib"
            }
        """.trimIndent())

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
        assertTrue(generatedFile.exists(), $$"Generated Kotlin file should exist at $generatedFile")

        // 3. 生成されたファイルに意図したシンボルが含まれているか
        val content = generatedFile.readText()
        assertTrue(content.contains("LibTestcrate"), "Should contain the Lib object")
        assertTrue(content.contains("fun hello"), "Should contain the generated method")
    }
}
