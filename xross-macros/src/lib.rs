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

/// Macro to define a class with its fields and methods in a DSL.
#[proc_macro]
pub fn xross_class(input: TokenStream) -> TokenStream {
    macros::xross_class::impl_xross_class(input)
}

/// Attribute macro for methods within an `impl` block.
#[proc_macro_attribute]
pub fn xross_methods(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input_impl = parse_macro_input!(item as ItemImpl);
    macros::attribute::impl_xross_class_attribute(attr.into(), input_impl).into()
}

/// Attribute macro for standalone functions.
#[proc_macro_attribute]
pub fn xross_function(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input_fn = parse_macro_input!(item as syn::ItemFn);
    macros::attribute::impl_xross_function_attribute(attr.into(), input_fn).into()
}

/// Macro to define standalone functions in a DSL.
#[proc_macro]
pub fn xross_function_dsl(input: TokenStream) -> TokenStream {
    macros::xross_function::impl_xross_function(input)
}
