use crate::{XrossField, XrossMethod};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum XrossDefinition {
    Struct(XrossStruct),
    Enum(XrossEnum),
    Opaque(XrossOpaque), // 追加
}

impl XrossDefinition {
    pub fn signature(&self) -> &str {
        match self {
            XrossDefinition::Struct(s) => &s.signature,
            XrossDefinition::Enum(e) => &e.signature,
            XrossDefinition::Opaque(o) => &o.signature,
        }
    }
    pub fn name(&self) -> &str {
        match self {
            XrossDefinition::Struct(s) => &s.name,
            XrossDefinition::Enum(e) => &e.name,
            XrossDefinition::Opaque(o) => &o.name,
        }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossStruct {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossEnum {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub variants: Vec<XrossVariant>,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
}

// 追加: メソッドやフィールドを持たない、ポインタ管理専用の定義
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossOpaque {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
    pub is_clonable: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossVariant {
    pub name: String,
    pub fields: Vec<XrossField>,
    pub docs: Vec<String>,
}
