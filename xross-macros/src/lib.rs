mod codegen;
mod macros;
mod metadata;
mod types;
mod utils;

use proc_macro::TokenStream;
use syn::{Item, ItemImpl, parse_macro_input};

/// Derive macro for `XrossClass`.
/// Generates metadata and FFI wrappers for a struct or enum.
#[proc_macro_derive(XrossClass, attributes(xross_field, xross_package, xross))]
pub fn xross_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as Item);
    macros::derive::impl_xross_class_derive(input).into()
}

/// Attribute macro for `xross_class`.
/// Applied to an `impl` block to generate FFI wrappers for methods.
#[proc_macro_attribute]
pub fn xross_class(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input_impl = parse_macro_input!(item as ItemImpl);
    macros::attribute::impl_xross_class_attribute(attr.into(), input_impl).into()
}

/// Macro to define an opaque class managed via pointers.
#[proc_macro]
pub fn opaque_class(input: TokenStream) -> TokenStream {
    macros::opaque::impl_opaque_class(input.into()).into()
}

/// Macro to define an external class for which only FFI bindings are needed.
#[proc_macro]
pub fn external_class(input: TokenStream) -> TokenStream {
    macros::external::impl_external_class(input)
}

/// Macro to define an external method.
#[proc_macro]
pub fn external_method(input: TokenStream) -> TokenStream {
    macros::external::impl_external_method(input)
}

/// Macro to define an external constructor.
#[proc_macro]
pub fn external_new(input: TokenStream) -> TokenStream {
    macros::external::impl_external_new(input)
}

/// Macro to define an external field.
#[proc_macro]
pub fn external_field(input: TokenStream) -> TokenStream {
    macros::external::impl_external_field(input)
}
