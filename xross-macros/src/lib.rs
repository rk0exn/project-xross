mod attribute_macro;
mod derive_macro;
mod ffi;
mod metadata;
mod opaque_macro;
mod type_mapping;
mod type_resolver;
mod utils;

use proc_macro::TokenStream;
use syn::{parse_macro_input, Item, ItemImpl};

#[proc_macro_derive(XrossClass, attributes(xross_field, xross_package, xross))]
pub fn xross_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as Item);
    derive_macro::impl_xross_class_derive(input).into()
}

#[proc_macro_attribute]
pub fn xross_class(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input_impl = parse_macro_input!(item as ItemImpl);
    attribute_macro::impl_xross_class_attribute(attr.into(), input_impl).into()
}

#[proc_macro]
pub fn opaque_class(input: TokenStream) -> TokenStream {
    opaque_macro::impl_opaque_class(input.into()).into()
}
