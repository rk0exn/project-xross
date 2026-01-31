use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum XrossType {
    Pointer,
    I32,
    I64,
    F32,
    F64,
    Void,
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
pub struct XrossMethod {
    pub name: String,
    pub symbol: String,
    pub is_constructor: bool,
    pub args: Vec<XrossType>,
    pub ret: XrossType,
    pub docs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossClass {
    pub package_name: String,
    pub struct_name: String,
    pub docs: Vec<String>, // クラス自体のコメント
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
}
