pub mod attributes;
pub mod ordinal;

pub use attributes::*;
pub use ordinal::*;

use heck::ToSnakeCase;
use xross_metadata::{HandleMode, ThreadSafety};

pub fn get_symbol_prefix(package_name: &str) -> String {
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    if package_name.is_empty() {
        crate_name
    } else {
        format!("{}_{}", crate_name, package_name.replace(".", "_"))
    }
}

pub fn register_xross_function(
    package_name: &str,
    name_str: &str,
    ffi_data: &crate::codegen::ffi::MethodFfiData,
    handle_mode: HandleMode,
    safety: ThreadSafety,
    ret_ty: &xross_metadata::XrossType,
    docs: Vec<String>,
) {
    use crate::metadata::save_definition;
    use xross_metadata::{XrossDefinition, XrossMethod};

    let method_meta = XrossMethod {
        name: name_str.to_string(),
        symbol: ffi_data.symbol_name.clone(),
        method_type: ffi_data.method_type,
        handle_mode,
        safety,
        is_constructor: false,
        is_default: false,
        is_async: ffi_data.is_async,
        args: ffi_data.args_meta.clone(),
        ret: ret_ty.clone(),
        docs: docs.clone(),
    };

    let definition = xross_metadata::XrossFunction {
        signature: crate::codegen::ffi::build_signature(package_name, name_str),
        symbol: ffi_data.symbol_name.clone(),
        package_name: package_name.to_string(),
        name: name_str.to_string(),
        method: method_meta,
        docs,
    };

    save_definition(&XrossDefinition::Function(definition));
}

pub fn build_symbol_base(crate_name: &str, package: &str, type_name: &str) -> String {
    let type_snake = type_name.to_snake_case();
    if package.is_empty() {
        format!("{}_{}", crate_name, type_snake)
    } else {
        format!("{}_{}_{}", crate_name, package.replace(".", "_"), type_snake)
    }
}
