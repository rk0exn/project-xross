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
// ... (existing XrossStruct)
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossStruct {
    /// Unique signature of the struct.
    pub signature: String,
    /// Prefix for native symbols.
    pub symbol_prefix: String,
    /// Target JVM package name.
    pub package_name: String,
    /// Name of the struct.
    pub name: String,
    /// Fields of the struct.
    pub fields: Vec<XrossField>,
    /// Methods associated with the struct.
    pub methods: Vec<XrossMethod>,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Whether the struct implements Copy trait.
    pub is_copy: bool,
}

/// Metadata for a Rust enum to be bridged to JVM.
// ... (existing XrossEnum)
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossEnum {
    /// Unique signature of the enum.
    pub signature: String,
    /// Prefix for native symbols.
    pub symbol_prefix: String,
    /// Target JVM package name.
    pub package_name: String,
    /// Name of the enum.
    pub name: String,
    /// Variants of the enum.
    pub variants: Vec<XrossVariant>,
    /// Methods associated with the enum.
    pub methods: Vec<XrossMethod>,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Whether the enum implements Copy trait.
    pub is_copy: bool,
}

/// Metadata for an opaque type managed via pointers.
// ... (existing XrossOpaque)
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossOpaque {
    /// Unique signature of the opaque type.
    pub signature: String,
    /// Prefix for native symbols.
    pub symbol_prefix: String,
    /// Target JVM package name.
    pub package_name: String,
    /// Name of the opaque type.
    pub name: String,
    /// Fields (usually empty or internal).
    pub fields: Vec<XrossField>,
    /// Methods associated with the opaque type.
    pub methods: Vec<XrossMethod>,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Whether the type implements Clone trait.
    pub is_clonable: bool,
    /// Whether the type implements Copy trait.
    pub is_copy: bool,
}

/// Metadata for a standalone function.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossFunction {
    /// Unique signature of the function.
    pub signature: String,
    /// Native symbol name.
    pub symbol: String,
    /// Target JVM package name.
    pub package_name: String,
    /// Name of the function.
    pub name: String,
    /// The method metadata for this function.
    pub method: XrossMethod,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
}

/// Metadata for a single variant of an enum.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossVariant {
    /// Name of the variant.
    pub name: String,
    /// Fields of the variant (for tuple or struct variants).
    pub fields: Vec<XrossField>,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
}
