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
