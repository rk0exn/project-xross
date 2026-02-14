use crate::codegen::ffi::{
    MethodFfiData, build_signature, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::metadata::save_definition;
use proc_macro2::TokenStream;
use quote::quote;
use syn::parse::{Parse, ParseStream};
use syn::{Signature, Token};
use xross_metadata::{HandleMode, ThreadSafety, XrossDefinition, XrossMethod};

mod kw {
    syn::custom_keyword!(package);
    syn::custom_keyword!(critical);
    syn::custom_keyword!(panicable);
    syn::custom_keyword!(safety);
    syn::custom_keyword!(heap_access);
}

pub struct XrossFunctionInput {
    pub package_name: String,
    pub handle_mode: HandleMode,
    pub safety: ThreadSafety,
    pub signature: Signature,
}

impl Parse for XrossFunctionInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let mut package_name = String::new();
        let mut handle_mode = HandleMode::Normal;
        let mut safety_level = ThreadSafety::Lock;

        while !input.peek(Token![fn]) && !input.is_empty() {
            if input.peek(kw::package) {
                input.parse::<kw::package>()?;
                package_name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
            } else if input.peek(kw::critical) {
                input.parse::<kw::critical>()?;
                let mut allow_heap_access = false;
                if input.peek(syn::token::Paren) {
                    let content;
                    syn::parenthesized!(content in input);
                    if content.peek(kw::heap_access) {
                        content.parse::<kw::heap_access>()?;
                        allow_heap_access = true;
                    }
                }
                handle_mode = HandleMode::Critical { allow_heap_access };
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
            } else if input.peek(kw::panicable) {
                input.parse::<kw::panicable>()?;
                handle_mode = HandleMode::Panicable;
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
            } else if input.peek(kw::safety) {
                input.parse::<kw::safety>()?;
                let id = input.parse::<syn::Ident>()?;
                safety_level = match id.to_string().as_str() {
                    "Unsafe" => ThreadSafety::Unsafe,
                    "Atomic" => ThreadSafety::Atomic,
                    "Immutable" => ThreadSafety::Immutable,
                    "Lock" => ThreadSafety::Lock,
                    _ => return Err(input.error("Unknown safety level")),
                };
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
            } else {
                break;
            }
        }

        let signature = input.parse::<Signature>()?;
        if input.peek(Token![;]) {
            input.parse::<Token![;]>()?;
        }

        Ok(XrossFunctionInput {
            package_name,
            handle_mode,
            safety: safety_level,
            signature,
        })
    }
}

pub fn impl_xross_function(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = syn::parse_macro_input!(input as XrossFunctionInput);
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let package_name = input.package_name;
    let rust_fn_name = &input.signature.ident;
    let name_str = rust_fn_name.to_string();

    let symbol_prefix = if package_name.is_empty() {
        crate_name.clone()
    } else {
        format!("{}_{}", crate_name, package_name.replace(".", "_"))
    };

    let mut ffi_data = MethodFfiData::new(&symbol_prefix, rust_fn_name);
    let dummy_ident = syn::Ident::new("Global", proc_macro2::Span::call_site());
    process_method_args(&input.signature.inputs, &package_name, &dummy_ident, &mut ffi_data);

    let ret_ty = resolve_return_type(
        &input.signature.output,
        &[],
        &package_name,
        &dummy_ident,
    );

    let method_meta = XrossMethod {
        name: name_str.clone(),
        symbol: ffi_data.symbol_name.clone(),
        method_type: ffi_data.method_type,
        handle_mode: input.handle_mode,
        safety: input.safety,
        is_constructor: false,
        args: ffi_data.args_meta.clone(),
        ret: ret_ty.clone(),
        docs: vec![],
    };

    let definition = xross_metadata::XrossFunction {
        signature: build_signature(&package_name, &name_str),
        symbol: ffi_data.symbol_name.clone(),
        package_name: package_name.clone(),
        name: name_str,
        method: method_meta,
        docs: vec![],
    };

    save_definition(&XrossDefinition::Function(definition));

    let mut extra_functions = Vec::new();
    let call_args = &ffi_data.call_args;
    let inner_call = quote! { #rust_fn_name(#(#call_args),*) };
    write_ffi_function(
        &ffi_data,
        &ret_ty,
        &input.signature.output,
        inner_call,
        input.handle_mode,
        &mut extra_functions,
    );

    quote! { #(#extra_functions)* }.into()
}
