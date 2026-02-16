use serde::{Deserialize, Serialize};

/// Defines the thread safety level for accessing fields or calling methods.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum ThreadSafety {
    /// No synchronization. Fastest access but requires external synchronization or single-threaded use.
    Unsafe,

    /// Direct access without any safety checks (even beyond Unsafe).
    Direct,

    /// Mutual exclusion using read-write locks on the JVM side.
    /// Allows multiple concurrent readers or a single writer.
    Lock,

    /// Atomic operations using CAS (Compare-And-Swap).
    /// Provides thread-safe access without blocking other threads.
    Atomic,

    /// Immutable data. Set once at creation and read-only thereafter.
    /// Provides the highest level of safety and performance for shared data.
    Immutable,
}
