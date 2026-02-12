package org.xross.structures

import kotlinx.serialization.Serializable

@Serializable
enum class XrossThreadSafety {
    Unsafe,
    Lock,
    Atomic,
    Immutable,
}
