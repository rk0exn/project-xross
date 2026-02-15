use crate::{XrossField, XrossMethod};
use serde::{Deserialize, Serialize};

/// Represents the definition of a type shared between Rust and JVM.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum XrossDefinition {
    /// A structured data type with named fields.
    Struct(XrossStruct),
    /// An enumeration with multiple variants.
    Enum(XrossEnum),
    /// A type that is managed via pointers and does not expose its fields to JVM.
    Opaque(XrossOpaque),
    /// A standalone function.
    Function(XrossFunction),
}

impl XrossDefinition {
    /// Returns the unique signature of this definition.
    pub fn signature(&self) -> &str {
        match self {
            XrossDefinition::Struct(s) => &s.signature,
            XrossDefinition::Enum(e) => &e.signature,
            XrossDefinition::Opaque(o) => &o.signature,
            XrossDefinition::Function(f) => &f.signature,
        }
    }
    /// Returns the name of this definition.
    pub fn name(&self) -> &str {
        match self {
            XrossDefinition::Struct(s) => &s.name,
            XrossDefinition::Enum(e) => &e.name,
            XrossDefinition::Opaque(o) => &o.name,
            XrossDefinition::Function(f) => &f.name,
        }
    }
}

/// Metadata for a Rust struct to be bridged to JVM.
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
    pub is_copy: bool,
}

/// Metadata for a Rust enum to be bridged to JVM.
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
    pub is_copy: bool,
}

/// Metadata for a single variant of an enum.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossVariant {
    pub name: String,
    pub fields: Vec<XrossField>,
    pub docs: Vec<String>,
}

/// Metadata for an opaque type managed via pointers.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossOpaque {
    pub signature: String,
    pub symbol_prefix: String,
    pub package_name: String,
    pub name: String,
    pub fields: Vec<XrossField>,
    pub methods: Vec<XrossMethod>,
    pub docs: Vec<String>,
    pub is_clonable: bool,
    pub is_copy: bool,
}

/// Metadata for a standalone function.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossFunction {
    pub signature: String,
    pub symbol: String,
    pub package_name: String,
    pub name: String,
    pub method: XrossMethod,
    pub docs: Vec<String>,
}