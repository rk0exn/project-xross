use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    Owned,  // Inline (for fields) or Value (for returns)
    Boxed,  // Boxed pointer (Box<T>)
    Ref,    // Immutable reference (&T)
    MutRef, // Mutable reference (&mut T)
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum XrossType {
    Void,
    Bool,
    I8,
    I16,
    I32,
    I64,
    U16,
    F32,
    F64,
    Pointer,
    String,
    Object {
        signature: String,
        ownership: Ownership,
    },
}

impl XrossType {
    pub fn is_owned(&self) -> bool {
        match self {
            XrossType::Object { ownership, .. } => {
                matches!(ownership, Ownership::Owned | Ownership::Boxed)
            }
            _ => false,
        }
    }
}
