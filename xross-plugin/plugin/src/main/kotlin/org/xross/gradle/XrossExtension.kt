package org.xross.gradle

/**
 * Extension for configuring the Xross plugin.
 */
abstract class XrossExtension {
    /**
     * Path to the Rust project directory.
     */
    var rustProjectDir: String = ""

    /**
     * Default package name for generated Kotlin files.
     */
    var packageName: String = ""

    private var customMetadataDir: String? = null

    /**
     * Directory where Rust Xross metadata JSON files are located.
     * Defaults to "${rustProjectDir}/target/xross".
     */
    var metadataDir: String
        get() = customMetadataDir ?: "$rustProjectDir/target/xross"
        set(value) {
            customMetadataDir = value
        }
}
