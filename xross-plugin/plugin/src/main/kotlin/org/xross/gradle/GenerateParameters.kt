package org.xross.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters
import java.io.File

interface GenerateParameters : WorkParameters {
    val jsonFile: Property<File>
    val outputDir: DirectoryProperty
    val packageName: Property<String>
    val metadataDir: Property<File>
}
