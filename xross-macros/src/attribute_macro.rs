use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, ImplItem, ItemImpl, Pat, ReturnType, Type};
use crate::utils::*;
use crate::metadata::{load_definition, save_definition};
use crate::type_resolver::resolve_type_with_attr;
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossField, XrossMethod, XrossMethodType,
    XrossType,
};

pub fn impl_xross_class_attribute(_attr: TokenStream, mut input_impl: ItemImpl) -> TokenStream {
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("xross_class must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident)
        .expect("XrossClass definition not found. Apply #[derive(XrossClass)] first.");

    let (package_name, symbol_base, _is_struct) = match &definition {
        XrossDefinition::Struct(s) => (s.package_name.clone(), s.symbol_prefix.clone(), true),
        XrossDefinition::Enum(e) => (e.package_name.clone(), e.symbol_prefix.clone(), false),
        XrossDefinition::Opaque(_) => {
            panic!("Unsupported. Why is there Opaque??????")
        }
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;
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
            let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
            let export_ident = format_ident!("{}", symbol_name);

            let mut method_type = XrossMethodType::Static;
            let mut args_meta = Vec::new();
            let mut c_args = Vec::new();
            let mut call_args = Vec::new();
            let mut conversion_logic = Vec::new();

            for input in &method.sig.inputs {
                match input {
                    FnArg::Receiver(receiver) => {
                        let arg_ident = format_ident!("_self");
                        if receiver.reference.is_none() {
                            method_type = XrossMethodType::OwnedInstance;
                            c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                            call_args.push(
                                quote! { *Box::from_raw(#arg_ident as *mut #type_name_ident) },
                            );
                        } else {
                            method_type = if receiver.mutability.is_some() {
                                XrossMethodType::MutInstance
                            } else {
                                XrossMethodType::ConstInstance
                            };
                            c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                            call_args.push(if receiver.mutability.is_some() {
                                quote!(&mut *(#arg_ident as *mut #type_name_ident))
                            } else {
                                quote!(&*(#arg_ident as *const #type_name_ident))
                            });
                        }
                    }
                    FnArg::Typed(pat_type) => {
                        let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                            id.ident.to_string()
                        } else {
                            "arg".into()
                        };
                        let arg_ident = format_ident!("{}", arg_name);
                        let xross_ty = resolve_type_with_attr(
                            &pat_type.ty,
                            &pat_type.attrs,
                            &package_name,
                            Some(type_name_ident),
                        );

                        args_meta.push(XrossField {
                            name: arg_name.clone(),
                            ty: xross_ty.clone(),
                            safety: extract_safety_attr(&pat_type.attrs, ThreadSafety::Lock),
                            docs: vec![],
                        });

                        match xross_ty {
                            XrossType::String => {
                                let raw_name = format_ident!("{}_raw", arg_ident);
                                c_args.push(quote! { #raw_name: *const std::ffi::c_char });
                                conversion_logic.push(quote! {
                                    let #arg_ident = unsafe {
                                        if #raw_name.is_null() { "" }
                                        else { std::ffi::CStr::from_ptr(#raw_name).to_str().unwrap_or("") }
                                    };
                                });
                                let is_string_owned = if let Type::Path(p) = &*pat_type.ty {
                                    p.path.is_ident("String")
                                } else {
                                    false
                                };
                                if is_string_owned {
                                    call_args.push(quote! { #arg_ident.to_string() });
                                } else {
                                    call_args.push(quote! { #arg_ident });
                                }
                            }
                            XrossType::Object { .. } => {
                                let raw_ty = &pat_type.ty;
                                c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                                if let Type::Reference(_) = &*pat_type.ty {
                                    call_args.push(quote! { &*(#arg_ident as *mut #raw_ty) });
                                } else {
                                    call_args.push(
                                        quote! { *Box::from_raw(#arg_ident as *mut #raw_ty) },
                                    );
                                }
                            }
                            _ => {
                                let rust_type_token = &pat_type.ty;
                                c_args.push(quote! { #arg_ident: #rust_type_token });
                                call_args.push(quote! { #arg_ident });
                            }
                        }
                    }
                }
            }

            let ret_ty = if is_new {
                let sig = if package_name.is_empty() {
                    type_name_ident.to_string()
                } else {
                    format!("{}.{}", package_name, type_name_ident)
                };
                XrossType::Object { signature: sig, ownership: Ownership::Owned }
            } else {
                match &method.sig.output {
                    ReturnType::Default => XrossType::Void,
                    ReturnType::Type(_, ty) => {
                        let mut xross_ty = resolve_type_with_attr(
                            ty,
                            &method.attrs,
                            &package_name,
                            Some(type_name_ident),
                        );

                        let ownership = match &**ty {
                            Type::Reference(r) => {
                                if r.mutability.is_some() {
                                    Ownership::MutRef
                                } else {
                                    Ownership::Ref
                                }
                            }
                            _ => Ownership::Owned,
                        };

                        match &mut xross_ty {
                            XrossType::Object { ownership: o, .. } => {
                                *o = ownership;
                            }
                            _ => {}
                        }
                        xross_ty
                    }
                }
            };
            methods_meta.push(XrossMethod {
                name: rust_fn_name.to_string(),
                symbol: symbol_name.clone(),
                method_type,
                safety: extract_safety_attr(&method.attrs, ThreadSafety::Lock),
                is_constructor: is_new,
                args: args_meta,
                ret: ret_ty.clone(),
                docs: extract_docs(&method.attrs),
            });

            let inner_call = quote! { #type_name_ident::#rust_fn_name(#(#call_args),*) };
            let (c_ret_type, wrapper_body) = match &ret_ty {
                XrossType::Void => (quote! { () }, quote! { #inner_call; }),
                XrossType::String => (
                    quote! { *mut std::ffi::c_char },
                    quote! { std::ffi::CString::new(#inner_call).unwrap_or_default().into_raw() },
                ),
                XrossType::Object { ownership, .. } => match ownership {
                    Ownership::Ref | Ownership::MutRef => (
                        quote! { *mut std::ffi::c_void },
                        quote! { #inner_call as *const _ as *mut std::ffi::c_void },
                    ),
                    Ownership::Owned => (
                        quote! { *mut std::ffi::c_void },
                        quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
                    ),
                    Ownership::Boxed => (
                        quote! { *mut std::ffi::c_void },
                        quote! { Box::into_raw(#inner_call) as *mut std::ffi::c_void },
                    ),
                },
                XrossType::Option(inner) => {
                    match &**inner {
                        XrossType::String => (
                            quote! { *mut std::ffi::c_char },
                            quote! {
                                match #inner_call {
                                    Some(s) => std::ffi::CString::new(s).unwrap_or_default().into_raw(),
                                    None => std::ptr::null_mut(),
                                }
                            },
                        ),
                        XrossType::Object { .. } => (
                            quote! { *mut std::ffi::c_void },
                            quote! {
                                match #inner_call {
                                    Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                                    None => std::ptr::null_mut(),
                                }
                            },
                        ),
                        _ => {
                            (
                                quote! { *mut std::ffi::c_void },
                                quote! {
                                    match #inner_call {
                                        Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                                        None => std::ptr::null_mut(),
                                    }
                                },
                            )
                        }
                    }
                }
                XrossType::Result { ok, err } => {
                    let gen_ptr = |ty: &XrossType, val_ident: proc_macro2::TokenStream| match ty {
                        XrossType::String => quote! {
                            std::ffi::CString::new(#val_ident).unwrap_or_default().into_raw() as *mut std::ffi::c_void
                        },
                        _ => quote! {
                            Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void
                        },
                    };
                    let ok_ptr_logic = gen_ptr(ok, quote! { val });
                    let err_ptr_logic = gen_ptr(err, quote! { e });

                    (
                        quote! { xross_core::XrossResult },
                        quote! {
                            match #inner_call {
                                Ok(val) => xross_core::XrossResult {
                                    ok_ptr: #ok_ptr_logic,
                                    err_ptr: std::ptr::null_mut(),
                                },
                                Err(e) => xross_core::XrossResult {
                                    ok_ptr: std::ptr::null_mut(),
                                    err_ptr: #err_ptr_logic,
                                },
                            }
                        },
                    )
                }
                _ => {
                    let raw_ret = if let ReturnType::Type(_, ty) = &method.sig.output {
                        quote! { #ty }
                    } else {
                        quote! { () }
                    };
                    (raw_ret, quote! { #inner_call })
                }
            };
            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
                    #(#conversion_logic)*
                    #wrapper_body
                }
            });
        }
    }

    match &mut definition {
        XrossDefinition::Struct(s) => s.methods.extend(methods_meta),
        XrossDefinition::Enum(e) => e.methods.extend(methods_meta),
        XrossDefinition::Opaque(_) => {
            panic!("Unsupported. Why is there Opaque??????")
        }
    }

    save_definition(&definition);
    quote! { #input_impl #(#extra_functions)* }
}
