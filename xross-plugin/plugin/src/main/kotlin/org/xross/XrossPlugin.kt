package org.xross

import com.squareup.kotlinpoet.*
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import javax.inject.Inject

class XrossPlugin @Inject constructor() : Plugin<Project> {

    private val json = Json { ignoreUnknownKeys = true }

    override fun apply(project: Project) {
        val extension = project.extensions.create("xross", XrossExtension::class.java)

        // 出力先ディレクトリのProvider。build/generated/... を指す
        val outputDirProvider = project.layout.buildDirectory.dir("generated/source/xross/main/kotlin")

        // 1. SourceSet への自動登録
        // 評価後(afterEvaluate)に行うことで、extensionの値が確定してからパスを解決する
        project.afterEvaluate {
            project.extensions.findByType(SourceSetContainer::class.java)?.named("main") { ss ->
                ss.java.srcDir(outputDirProvider)
            }
        }

        // 2. 生成タスクの登録
        val generateTask = project.tasks.register("generateXrossBindings") { task ->
            // タスクの入出力設定
            val rustProjectDir = extension.rustProjectDir
            val metadataDir = project.file(rustProjectDir).resolve("target/xross")

            task.inputs.dir(metadataDir).withPropertyName("metadataDir").optional()
            task.outputs.dir(outputDirProvider).withPropertyName("outputDir")

            task.doLast {
                val outDir = outputDirProvider.get().asFile
                if (!metadataDir.exists()) {
                    println("Xross: Metadata dir not found at ${metadataDir.absolutePath}")
                    return@doLast
                }

                outDir.deleteRecursively()
                outDir.mkdirs()

                metadataDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                    val meta = json.decodeFromString<XrossClass>(file.readText())
                    val targetPackage = extension.packageName.ifBlank { meta.packageName }
                    generateKotlinFile(meta, outDir, extension.crateName, targetPackage)
                }
            }
        }

//        // 3. Kotlinコンパイル前に自動実行されるようフック
//        project.tasks.withType(KotlinCompile::class.java).configureEach {
//            it.dependsOn(generateTask)
//        }
    }

    private fun generateKotlinFile(meta: XrossClass, outputDir: File, crateName: String, targetPackage: String) {
        val libClassName = "Lib${crateName.replaceFirstChar { it.uppercase() }}"
        val memorySegment = ClassName("java.lang.foreign", "MemorySegment")
        val functionDescriptor = ClassName("java.lang.foreign", "FunctionDescriptor")
        val symbolLookup = ClassName("java.lang.foreign", "SymbolLookup")

        val fileSpec = FileSpec.builder(targetPackage, meta.structName)
            .addImport("java.lang.foreign", "ValueLayout", "Linker", "Arena")
            .addImport("java.lang.invoke", "MethodHandle")

        // --- Lib Object (Internal) ---
        val libObject = TypeSpec.objectBuilder(libClassName)
            .addModifiers(KModifier.INTERNAL)
            .addProperty(PropertySpec.builder("linker", ClassName("java.lang.foreign", "Linker"), KModifier.PRIVATE)
                .initializer("Linker.nativeLinker()").build())
            .addProperty(PropertySpec.builder("lookup", symbolLookup, KModifier.PRIVATE, KModifier.LATEINIT).mutable().build())
            .apply {
                meta.methods.forEach {
                    addProperty(PropertySpec.builder("h_${it.name}", ClassName("java.lang.invoke", "MethodHandle"), KModifier.INTERNAL, KModifier.LATEINIT).mutable().build())
                }
            }
            .addFunction(FunSpec.builder("init")
                .addCode(generateInitBody(crateName, meta, functionDescriptor))
                .build())
            .build()

        // --- Struct Class ---
        val structClass = TypeSpec.classBuilder(meta.structName)
            .primaryConstructor(FunSpec.constructorBuilder()
                .addParameter("segment", memorySegment)
                .addModifiers(KModifier.INTERNAL).build())
            .addProperty(PropertySpec.builder("segment", memorySegment, KModifier.PRIVATE).initializer("segment").build())
            .apply {
                meta.methods.filter { !it.isConstructor }.forEach {
                    addFunction(generateMethod(it, libClassName, false, meta.structName, targetPackage))
                }
            }
            .addType(TypeSpec.companionObjectBuilder()
                .apply {
                    meta.methods.filter { it.isConstructor }.forEach {
                        addFunction(generateMethod(it, libClassName, true, meta.structName, targetPackage))
                    }
                }.build())
            .build()

        fileSpec.addType(libObject).addType(structClass).build().writeTo(outputDir)
    }

    private fun generateInitBody(crateName: String, meta: XrossClass, functionDescriptor: ClassName): CodeBlock {
        val linker = ClassName("java.lang.foreign", "Linker")
        return CodeBlock.builder()
            .addStatement("if (::lookup.isInitialized) return")
            .addStatement("val os = System.getProperty(\"os.name\").lowercase()")
            .addStatement("val ext = if (os.contains(\"win\")) \"dll\" else if (os.contains(\"mac\")) \"dylib\" else \"so\"")
            .addStatement("val name = \"lib${crateName.replace("-", "_")}.\$ext\"")
            .addStatement("val stream = this::class.java.getResourceAsStream(\"/xross/natives/\$name\") ?: throw RuntimeException(\"Missing \$name\")")
            .addStatement("val file = java.io.File.createTempFile(\"xross_\", \"_\$name\").apply { deleteOnExit() }")
            .addStatement("stream.use { s -> file.outputStream().use { s.copyTo(it) } }")
            .addStatement("System.load(file.absolutePath)")
            .addStatement("lookup = SymbolLookup.libraryLookup(file.toPath(), Arena.global())")
            .apply {
                meta.methods.forEach { method ->
                    add("\n// %L\n", method.name)
                    add("h_${method.name} = linker.downcallHandle(\n")
                    indent()
                    add("lookup.find(%S).get(),\n", method.symbol)
                    add("%T.of(\n", functionDescriptor)
                    indent()
                    if (method.ret != XrossType.Void) add("%M,\n", mapXrossTypeToMember(method.ret))
                    if (!method.isConstructor) add("ValueLayout.ADDRESS,\n")
                    method.args.forEach { add("%M,\n", mapXrossTypeToMember(it)) }
                    unindent()
                    add(")\n")
                    unindent()
                    add(")\n")
                }
            }
            .build()
    }

    private fun generateMethod(method: XrossMethod, libName: String, isStatic: Boolean, structName: String, pkg: String): FunSpec {
        val memorySegment = ClassName("java.lang.foreign", "MemorySegment")
        return FunSpec.builder(method.name)
            .addKdoc(method.docs.joinToString("\n"))
            .apply {
                if (isStatic) addParameter("arena", ClassName("java.lang.foreign", "Arena"))
                method.args.forEachIndexed { i, type -> addParameter("arg$i", mapXrossTypeToKotlin(type)) }
            }
            .apply {
                val callArgs = (if (!isStatic) listOf("this.segment") else emptyList()) +
                        method.args.indices.map { "arg$it" }
                val call = "$libName.h_${method.name}.invokeExact(${callArgs.joinToString(", ")})"

                if (method.isConstructor) {
                    returns(ClassName(pkg, structName))
                    addStatement("return $structName($call as %T)", memorySegment)
                } else if (method.ret != XrossType.Void) {
                    returns(mapXrossTypeToKotlin(method.ret))
                    addStatement("return $call as %T", mapXrossTypeToKotlin(method.ret))
                } else {
                    addStatement(call)
                }
            }
            .build()
    }

    private fun mapXrossTypeToKotlin(type: XrossType) = when (type) {
        XrossType.I32 -> INT
        XrossType.I64 -> LONG
        XrossType.F32 -> FLOAT
        XrossType.F64 -> DOUBLE
        XrossType.Pointer -> ClassName("java.lang.foreign", "MemorySegment")
        XrossType.Void -> UNIT
    }

    private fun mapXrossTypeToMember(type: XrossType) = MemberName("java.lang.foreign.ValueLayout", when (type) {
        XrossType.I32 -> "JAVA_INT"
        XrossType.I64 -> "JAVA_LONG"
        XrossType.F32 -> "JAVA_FLOAT"
        XrossType.F64 -> "JAVA_DOUBLE"
        XrossType.Pointer, XrossType.Void -> "ADDRESS"
    })
}
