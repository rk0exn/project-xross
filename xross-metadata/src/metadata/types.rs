use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum Ownership {
    Owned,  // 所有権あり (dropが必要)
    Ref,    // 不変参照
    MutRef, // 可変参照
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
            XrossType::Object { ownership, .. } => *ownership == Ownership::Owned,
            _ => false,
        }
    }
}
