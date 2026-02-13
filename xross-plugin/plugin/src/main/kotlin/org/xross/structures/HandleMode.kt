package org.xross.structures

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Defines how the native method handle should be invoked.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class HandleMode {
    /** Standard execution. */
    @Serializable
    @SerialName("normal")
    data object Normal : HandleMode()

    /** Optimized for extremely short-running, non-blocking computations. Maps to Linker.Option.critical(false) in Java by default. */
    @Serializable
    @SerialName("critical")
    data class Critical(val allowHeapAccess: Boolean = false) : HandleMode()

    /** Can panic and should be caught to propagate as an exception to JVM. */
    @Serializable
    @SerialName("panicable")
    data object Panicable : HandleMode()
}
