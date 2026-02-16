mod types;
pub use types::*;

use crate::metadata::ThreadSafety;
use crate::{XrossField, XrossType};
use serde::{Deserialize, Serialize};

/// Metadata for a method to be bridged to JVM.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossMethod {
    /// Name of the method.
    pub name: String,
    /// Native symbol name.
    pub symbol: String,
    /// Type of the method (Static, Instance, etc.).
    pub method_type: XrossMethodType,
    /// How the method handle should be invoked.
    pub handle_mode: HandleMode,
    /// Whether this method is a constructor.
    pub is_constructor: bool,
    /// Whether this method is the default constructor.
    pub is_default: bool,
    /// Whether this method is asynchronous.
    pub is_async: bool,
    /// Arguments of the method.
    pub args: Vec<XrossField>,
    /// Return type of the method.
    pub ret: XrossType,
    /// Documentation comments from Rust source.
    pub docs: Vec<String>,
    /// Thread safety level for calling this method.
    pub safety: ThreadSafety,
}
