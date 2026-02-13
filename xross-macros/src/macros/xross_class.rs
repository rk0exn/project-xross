pub mod expander;
pub mod parser;

use crate::macros::xross_class::parser::XrossClassInput;
use syn::parse_macro_input;

pub fn impl_xross_class(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as XrossClassInput);
    expander::impl_xross_class(input)
}
