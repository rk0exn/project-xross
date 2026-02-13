use crate::codegen::ffi::{
    MethodFfiData, build_signature, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::metadata::{load_definition, save_definition};
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::quote;
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
    }

    save_definition(&definition);
    quote! { #input_impl #(#extra_functions)* }
}
