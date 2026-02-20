use serde::{Deserialize, Serialize};

/// Represents the ownership model of a type when bridged between Rust and JVM.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    /// Owned value, either inline in a struct or passed by value.
    Owned,
    /// Value wrapped in a Box (Box<T>).
    Boxed,
    /// Immutable reference (&T).
    Ref,
    /// Mutable reference (&mut T).
    MutRef,
}

/// Represents the data types supported by the Xross bridge.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum XrossType {
    /// No value.
    Void,
    /// Boolean value.
    Bool,
    /// 8-bit signed integer.
    I8,
    /// 8-bit unsigned integer.
    U8,
    /// 16-bit signed integer.
    I16,
    /// 16-bit unsigned integer.
    U16,
    /// 32-bit signed integer.
    I32,
    /// 32-bit unsigned integer.
    U32,
    /// 64-bit signed integer.
    I64,
    /// 64-bit unsigned integer.
    U64,
    /// Pointer-sized signed integer.
    ISize,
    /// Pointer-sized unsigned integer.
    USize,
    /// 32-bit floating point number.
    F32,
    /// 64-bit floating point number.
    F64,
    /// Raw pointer.
    Pointer,
    /// UTF-8 string.
    String,
    /// A slice of values (&[T]).
    Slice(Box<XrossType>),
    /// An owned vector of values (Vec<T>).
    Vec(Box<XrossType>),
    /// Double-ended queue (VecDeque<T>).
    VecDeque(Box<XrossType>),
    /// Linked list (LinkedList<T>).
    LinkedList(Box<XrossType>),
    /// Hash set (HashSet<T>).
    HashSet(Box<XrossType>),
    /// B-tree set (BTreeSet<T>).
    BTreeSet(Box<XrossType>),
    /// Binary heap (BinaryHeap<T>).
    BinaryHeap(Box<XrossType>),
    /// Hash map (HashMap<K, V>).
    HashMap { key: Box<XrossType>, value: Box<XrossType> },
    /// B-tree map (BTreeMap<K, V>).
    BTreeMap { key: Box<XrossType>, value: Box<XrossType> },
    /// A user-defined object type.
    Object {
        /// Unique signature of the object type.
        signature: String,
        /// Ownership model for this object.
        ownership: Ownership,
    },
    /// An optional value.
    Option(Box<XrossType>),
    /// A result value that can be either Ok or Err.
    Result {
        /// Type of the successful value.
        ok: Box<XrossType>,
        /// Type of the error value.
        err: Box<XrossType>,
    },
    /// An asynchronous computation.
    Async(Box<XrossType>),
}

impl XrossType {
    /// Returns true if the type represents an owned value.
    pub fn is_owned(&self) -> bool {
        match self {
            XrossType::Object { ownership, .. } => {
                matches!(ownership, Ownership::Owned | Ownership::Boxed)
            }
            XrossType::Result { .. }
            | XrossType::Option(_)
            | XrossType::Vec(_)
            | XrossType::VecDeque(_)
            | XrossType::LinkedList(_)
            | XrossType::HashSet(_)
            | XrossType::BTreeSet(_)
            | XrossType::BinaryHeap(_)
            | XrossType::HashMap { .. }
            | XrossType::BTreeMap { .. } => true,
            _ => false,
        }
    }
}
