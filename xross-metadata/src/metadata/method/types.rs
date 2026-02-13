use serde::{Deserialize, Serialize};

/// Represents the type of a method based on its receiver.
#[derive(Serialize, Deserialize, Debug, Clone, Copy)]
pub enum XrossMethodType {
    /// A static function that does not take a receiver (self).
    Static,
    /// An instance method that takes an immutable reference to self (&self).
    ConstInstance,
    /// An instance method that takes a mutable reference to self (&mut self).
    MutInstance,
    /// An instance method that consumes ownership of self.
    /// The handle on the JVM side must be invalidated after this call.
    OwnedInstance,
}

/// Defines how the native method handle should be invoked.
#[derive(Serialize, Deserialize, Debug, Clone, Copy, Default, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum HandleMode {
    /// Standard execution.
    #[default]
    Normal,
    /// Optimized for extremely short-running, non-blocking computations.
    /// Maps to Linker.Option.critical(false) in Java by default.
    Critical {
        /// Whether the method is allowed to access the Java heap.
        #[serde(default, rename = "allowHeapAccess")]
        allow_heap_access: bool,
    },
    /// Can panic and should be caught to propagate as an exception to JVM.
    Panicable,
}
