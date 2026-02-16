package org.xross.structures

import kotlinx.serialization.Serializable

/**
 * Defines the thread safety level for accessing fields or calling methods.
 */
@Serializable
enum class XrossThreadSafety {
    /** No synchronization. */
    Unsafe,

    /** Direct access without any safety checks. */
    Direct,

    /** Mutual exclusion using locks. */
    Lock,

    /** Atomic operations. */
    Atomic,

    /** Immutable data. */
    Immutable,
}
