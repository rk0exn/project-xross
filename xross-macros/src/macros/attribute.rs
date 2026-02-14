use crate::codegen::ffi::{
    MethodFfiData, build_signature, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::metadata::{load_definition, save_definition};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::quote;
use syn::parse::Parser;
use syn::{ImplItem, ItemImpl, Type};
use xross_metadata::{Ownership, ThreadSafety, XrossDefinition, XrossMethod};

pub fn impl_xross_class_attribute(_attr: TokenStream, mut input_impl: ItemImpl) -> TokenStream {
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("xross_methods must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident).expect(
        "XrossClass definition not found. Apply #[derive(XrossClass)] or xross_class! first.",
    );

    let (package_name, symbol_base) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone()),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone()),
        XrossDefinition::Opaque(o) => (o.package_name.clone(), o.symbol_prefix.clone()),
        XrossDefinition::Function(f) => (f.package_name.clone(), f.symbol.clone()),
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;

            let handle_mode = extract_handle_mode(&method.attrs);

            method.attrs.retain(|attr| {
                if attr.path().is_ident("xross_new") {
                    is_new = true;
                    false
                } else if attr.path().is_ident("xross_method") {
                    is_method = true;
                    false
                } else {
                    true
                }
            });

            if !is_new && !is_method {
                continue;
            }

            let rust_fn_name = &method.sig.ident;
            let is_async = method.sig.asyncness.is_some();
            let mut ffi_data = MethodFfiData::new(&symbol_base, rust_fn_name);
            ffi_data.is_async = is_async;

            process_method_args(&method.sig.inputs, &package_name, type_name_ident, &mut ffi_data);

            let ret_ty = if is_new {
                xross_metadata::XrossType::Object {
                    signature: build_signature(&package_name, &type_name_ident.to_string()),
                    ownership: Ownership::Owned,
                }
            } else {
                resolve_return_type(
                    &method.sig.output,
                    &method.attrs,
                    &package_name,
                    type_name_ident,
                )
            };

            let handle_mode = if is_new { xross_metadata::HandleMode::Normal } else { handle_mode };

            methods_meta.push(XrossMethod {
                name: rust_fn_name.to_string(),
                symbol: ffi_data.symbol_name.clone(),
                method_type: ffi_data.method_type,
                handle_mode,
                safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                is_constructor: is_new,
                is_async,
                args: ffi_data.args_meta.clone(),
                ret: ret_ty.clone(),
                docs: extract_docs(&method.attrs),
            });

            let call_args = &ffi_data.call_args;
            let inner_call = quote! { #type_name_ident::#rust_fn_name(#(#call_args),*) };
            write_ffi_function(
                &ffi_data,
                &ret_ty,
                &method.sig.output,
                inner_call,
                handle_mode,
                &mut extra_functions,
            );
        }
    }

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods.extend(methods_meta),
        XrossDefinition::Enum(e) => e.methods.extend(methods_meta),
        XrossDefinition::Opaque(o) => o.methods.extend(methods_meta),
        XrossDefinition::Function(_f) => {
            if !methods_meta.is_empty() {
                panic!("Cannot add methods to a standalone function definition.");
            }
        }
    }

    save_definition(&definition);
    quote! { #(#extra_functions)* #input_impl }
}

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

    let ret_ty = resolve_return_type(
        &input_fn.sig.output,
        &input_fn.attrs,
        &package_name,
        &dummy_ident,
    );

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
