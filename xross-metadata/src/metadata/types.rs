use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum XrossType {
    Void,
    Bool,
    I8,
    I16,
    I32,
    I64,
    U16, // Java Char
    F32,
    F64,
    Pointer,
    String,                // Rust String / &str
    Slice(Box<XrossType>), // Vec<T> / &[T]
}

impl XrossType {
    /// 型ごとのバイトサイズを返す
    pub fn size(&self) -> usize {
        match self {
            XrossType::Void => 0,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            XrossType::I64 | XrossType::F64 | XrossType::Pointer | XrossType::String => 8,
            XrossType::Slice(_) => 16, // Pointer(8) + Length(8)
        }
    }

    /// 型ごとのアライメントを返す
    pub fn align(&self) -> usize {
        match self {
            XrossType::Void => 1,
            XrossType::Bool | XrossType::I8 => 1,
            XrossType::I16 | XrossType::U16 => 2,
            XrossType::I32 | XrossType::F32 => 4,
            XrossType::I64 | XrossType::F64 | XrossType::Pointer | XrossType::String => 8,
            XrossType::Slice(_) => 8,
        }
    }
}

