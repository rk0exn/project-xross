use crate::codegen::ffi::{
    MethodFfiData, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::quote;
use syn::parse::Parser;
use xross_metadata::ThreadSafety;

pub fn impl_xross_function_attribute(attr: TokenStream, input_fn: syn::ItemFn) -> TokenStream {
    let mut package_name = String::new();
    let mut handle_mode = None;
    let mut safety = None;

    if !attr.is_empty() {
        let res = syn::meta::parser(|meta| {
            if meta.path.is_ident("package") {
                let value = meta.value()?;
                if let Ok(lit) = value.parse::<syn::LitStr>() {
                    package_name = lit.value();
                } else if let Ok(id) = value.parse::<syn::Ident>() {
                    package_name = id.to_string();
                }
            } else if meta.path.is_ident("critical") {
                let allow_heap_access = crate::utils::parse_critical_nested(&meta)?;
                handle_mode = Some(xross_metadata::HandleMode::Critical { allow_heap_access });
            } else if meta.path.is_ident("panicable") {
                handle_mode = Some(xross_metadata::HandleMode::Panicable);
            } else if meta.path.is_ident("safety") {
                let value = meta.value()?.parse::<syn::Ident>()?;
                safety = match value.to_string().as_str() {
                    "Unsafe" => Some(ThreadSafety::Unsafe),
                    "Atomic" => Some(ThreadSafety::Atomic),
                    "Immutable" => Some(ThreadSafety::Immutable),
                    "Lock" => Some(ThreadSafety::Lock),
                    _ => None,
                };
            }
            Ok(())
        })
        .parse2(attr);
        if let Err(e) = res {
            panic!("Failed to parse xross_function attributes: {}", e);
        }
    }

    let rust_fn_name = &input_fn.sig.ident;
    let name_str = rust_fn_name.to_string();
    let is_async = input_fn.sig.asyncness.is_some();

    let symbol_prefix = crate::utils::get_symbol_prefix(&package_name);

    let mut ffi_data = MethodFfiData::new(&symbol_prefix, rust_fn_name);
    ffi_data.is_async = is_async;
    // Standalone functions don't have a receiver, so we use a dummy ident for type_name_ident
    let dummy_ident = syn::Ident::new("Global", proc_macro2::Span::call_site());
    process_method_args(&input_fn.sig.inputs, &package_name, &dummy_ident, &mut ffi_data);

    let ret_ty =
        resolve_return_type(&input_fn.sig.output, &input_fn.attrs, &package_name, &dummy_ident);

    let handle_mode = handle_mode.unwrap_or_else(|| extract_handle_mode(&input_fn.attrs));
    let safety = safety.unwrap_or_else(|| extract_safety_attr(&input_fn.attrs, ThreadSafety::Lock));
    let docs = extract_docs(&input_fn.attrs);

    crate::utils::register_xross_function(
        &package_name,
        &name_str,
        &ffi_data,
        handle_mode,
        safety,
        &ret_ty,
        docs,
    );

    let mut extra_functions = Vec::new();
    let call_args = &ffi_data.call_args;
    let inner_call = quote! { #rust_fn_name(#(#call_args),*) };
    write_ffi_function(
        &ffi_data,
        &ret_ty,
        &input_fn.sig.output,
        inner_call,
        handle_mode,
        &mut extra_functions,
    );

    quote! { #(#extra_functions)* #input_fn }
}
