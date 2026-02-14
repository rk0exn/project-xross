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
            let mut ffi_data = MethodFfiData::new(&symbol_base, rust_fn_name);

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
    quote! { #input_impl #(#extra_functions)* }
}

pub fn impl_xross_function_attribute(attr: TokenStream, input_fn: syn::ItemFn) -> TokenStream {
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

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
                let mut allow_heap_access = false;
                if meta.input.peek(syn::token::Paren) {
                    let _ = meta.parse_nested_meta(|inner| {
                        if inner.path.is_ident("heap_access") {
                            allow_heap_access = true;
                        }
                        Ok(())
                    });
                }
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

    let symbol_prefix = if package_name.is_empty() {
        crate_name.clone()
    } else {
        format!("{}_{}", crate_name, package_name.replace(".", "_"))
    };

    let mut ffi_data = MethodFfiData::new(&symbol_prefix, rust_fn_name);
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

    let method_meta = XrossMethod {
        name: name_str.clone(),
        symbol: ffi_data.symbol_name.clone(),
        method_type: ffi_data.method_type,
        handle_mode,
        safety,
        is_constructor: false,
        args: ffi_data.args_meta.clone(),
        ret: ret_ty.clone(),
        docs: extract_docs(&input_fn.attrs),
    };

    let definition = xross_metadata::XrossFunction {
        signature: build_signature(&package_name, &name_str),
        symbol: ffi_data.symbol_name.clone(),
        package_name: package_name.clone(),
        name: name_str,
        method: method_meta,
        docs: extract_docs(&input_fn.attrs),
    };

    save_definition(&XrossDefinition::Function(definition));

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
