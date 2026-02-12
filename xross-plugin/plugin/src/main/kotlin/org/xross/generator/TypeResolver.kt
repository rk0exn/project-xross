package org.xross.generator

import kotlinx.serialization.json.Json
import org.xross.structures.XrossDefinition
import java.io.File

class TypeResolver(
    metadataDir: File,
) {
    private val shortNameToFqn = mutableMapOf<String, MutableSet<String>>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (metadataDir.exists()) {
            metadataDir.walkTopDown().filter { it.extension == "json" }.forEach { file ->
                try {
                    val def = json.decodeFromString<XrossDefinition>(file.readText())
                    val name = def.name
                    val fqn = def.signature
                    shortNameToFqn.getOrPut(name) { mutableSetOf() }.add(fqn)
                } catch (e: Exception) {
                    // Ignore malformed JSON during scanning
                }
            }
        }
    }

    fun resolve(
        signature: String,
        context: String = "Unknown",
    ): String {
        // すでにドットが含まれている場合は解決済みとみなす
        if (signature.contains('.')) return signature

        val candidates = shortNameToFqn[signature] ?: emptySet()
        return when (candidates.size) {
            0 -> {
                throw RuntimeException(
                    "\n[Xross Error] Failed to resolve type: '$signature'\n" +
                        "Context: $context\n" +
                        "Possible solutions:\n" +
                        "1. Ensure the target type has #[derive(JvmClass)] or opaque_class!.\n" +
                        "2. If the type is in another crate, ensure its metadata is available.\n",
                )
            }
            1 -> candidates.first()
            else -> {
                throw RuntimeException(
                    "\n[Xross Error] Ambiguous type reference: '$signature'\n" +
                        "Context: $context\n" +
                        "Multiple types with the same name were found in different packages:\n" +
                        candidates.joinToString("\n") { "  - $it" } +
                        "\nPlease use an explicit signature in Rust, for example:\n" +
                        "#[xross(struct = \"${candidates.first()}\")]\n",
                )
            }
        }
    }
}
