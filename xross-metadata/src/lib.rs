use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
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

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum XrossMethodType {
    /// staticな関数 (selfを取らない)
    Static,
    /// &self (不変参照)
    ConstInstance,
    /// &mut self (可変参照)
    MutInstance,
    /// self (所有権を消費する。呼んだ後はJava側のハンドルを無効化する必要がある)
    OwnedInstance,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossMethod {
    pub name: String,
    pub symbol: String,
    pub method_type: XrossMethodType,
    pub is_constructor: bool,
    pub args: Vec<XrossField>, // 型を Vec<XrossField> に変更
    pub ret: XrossType,
    pub docs: Vec<String>,
}
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossField {
    pub name: String,
    pub ty: XrossType,
    pub docs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossClass {
    pub package_name: String,
    pub struct_name: String,
    pub docs: Vec<String>,
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
}
