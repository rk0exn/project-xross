package org.xross

import com.squareup.kotlinpoet.*
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.io.File

class XrossPlugin @Inject constructor(
    private val execOperations: ExecOperations // Gradleが自動注入する
) : Plugin<Project> {

    private val json = Json { ignoreUnknownKeys = true }

    override fun apply(project: Project) {
        val extension = project.extensions.create("xross", XrossExtension::class.java)

        val generateTask = project.tasks.register("generateXrossBindings") { task ->
            task.doLast {
                val rustDir = project.file(extension.rustProjectDir)
                val metadataDir = rustDir.resolve("target/xross")
                val outputDir = project.file("build/generated/source/xross/main/kotlin")
                outputDir.deleteRecursively()
                outputDir.mkdirs()

                metadataDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                    val meta = json.decodeFromString<XrossClass>(file.readText())
                    generateKotlinCode(meta, outputDir, extension.crateName)
                }
            }
        }

        project.tasks.register("buildXrossNatives") { task ->
            task.dependsOn(generateTask)
            task.doLast {
                val os = System.getProperty("os.name").lowercase()
                val target = when {
                    os.contains("linux") -> "x86_64-unknown-linux-gnu"
                    os.contains("windows") -> "x86_64-pc-windows-msvc"
                    else -> "x86_64-apple-darwin"
                }

                // project.exec ではなく execOperations を使用
                execOperations.exec { spec ->
                    spec.workingDir = project.file(extension.rustProjectDir)
                    spec.commandLine("cargo", "zigbuild", "--release", "--target", target)
                }

                val resDir = project.file("src/main/resources/xross/natives")
                resDir.mkdirs()

                val ext = if (os.contains("win")) "dll" else if (os.contains("mac")) "dylib" else "so"
                val libName = "lib${extension.crateName.replace("-", "_")}.$ext"

                // project.copy の代わりに project.copy (これはProjectに存在する) を使うか
                // 確実に動く File API を使用
                val sourceFile = project.file("${extension.rustProjectDir}/target/$target/release/$libName")
                if (sourceFile.exists()) {
                    sourceFile.copyTo(File(resDir, libName), overwrite = true)
                }
            }
        }
    }

    private fun generateKotlinCode(meta: XrossClass, outputDir: File, crateName: String) {
        val libObjectName = "Lib${crateName.replaceFirstChar { it.uppercase() }}"
        val libPackage = meta.packageName

        // Panama API Classes
        val linkerClass = ClassName("java.lang.foreign", "Linker")
        val symbolLookupClass = ClassName("java.lang.foreign", "SymbolLookup")
        val arenaClass = ClassName("java.lang.foreign", "Arena")
        val memorySegmentClass = ClassName("java.lang.foreign", "MemorySegment")

        // 1. Lib{Crate} Object
        val libObject = TypeSpec.objectBuilder(libObjectName)
            .addProperty(PropertySpec.builder("linker", linkerClass)
                .initializer("%T.nativeLinker()", linkerClass).build())
            .addProperty(PropertySpec.builder("lookup", symbolLookupClass, KModifier.PRIVATE, KModifier.LATEINIT)
                .mutable(true).build())
            .addFunction(generateInitMethod(crateName, symbolLookupClass, arenaClass))
            .build()

        // 2. {Struct} Class
        val structClass = TypeSpec.classBuilder(meta.structName)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("segment", memorySegmentClass)
                .build())
            .addProperty(PropertySpec.builder("segment", memorySegmentClass)
                .initializer("segment").build())
            .apply {
                meta.methods.forEach { method ->
                    addFunction(generateMethod(method, libObjectName, memorySegmentClass))
                }
            }
            .build()

        FileSpec.builder(libPackage, meta.structName)
            .addType(libObject)
            .addType(structClass)
            .build()
            .writeTo(outputDir)
    }

    private fun generateInitMethod(crateName: String, symbolLookupClass: ClassName, arenaClass: ClassName): FunSpec {
        return FunSpec.builder("init")
            .addCode(
                $$"""
                val os = System.getProperty("os.name").lowercase()
                val ext = if (os.contains("win")) "dll" else if (os.contains("mac")) "dylib" else "so"
                val libName = "lib$${crateName.replace("-", "_")}.${ext}"
                val inputStream = this::class.java.getResourceAsStream("/xross/natives/${libName}") 
                    ?: throw RuntimeException("Native library not found: ${libName}")
                
                val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "xross_" + java.util.UUID.randomUUID())
                tempDir.mkdirs()
                val tempFile = java.io.File(tempDir, libName)
                tempFile.deleteOnExit()
                
                tempFile.outputStream().use { inputStream.copyTo(it) }
                System.load(tempFile.absolutePath)
                lookup = %T.libraryLookup(tempFile.toPath(), %T.global())
            """.trimIndent(), symbolLookupClass, arenaClass)
            .build()
    }

    private fun generateMethod(method: XrossMethod, libObjectName: String, memorySegmentClass: ClassName): FunSpec {
        return FunSpec.builder(method.name)
            .addKdoc(method.docs.joinToString("\n"))
            .addCode("// TODO: Add MethodHandle downcall for ${method.symbol}\n")
            .build()
    }
}