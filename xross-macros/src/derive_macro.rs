use crate::ffi::{generate_common_ffi, generate_enum_layout, generate_struct_layout};
use crate::metadata::save_definition;
use crate::type_resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::Item;
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossMethodType,
    XrossStruct, XrossType, XrossVariant,
};

pub fn impl_xross_class_derive(input: Item) -> TokenStream {
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let mut extra_functions = Vec::new();

    match input {
        syn::Item::Struct(s) => {
            let name = &s.ident;
            let package = extract_package(&s.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            let layout_logic = generate_struct_layout(&s);

            let mut fields = Vec::new();
            let methods = vec![XrossMethod {
                name: "clone".to_string(),
                symbol: format!("{}_clone", symbol_base),
                method_type: XrossMethodType::ConstInstance,
                is_constructor: false,
                args: vec![],
                ret: XrossType::Object {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    ownership: Ownership::Owned,
                },
                safety: ThreadSafety::Lock,
                docs: vec!["Creates a clone of the native object.".to_string()],
            }];

            if let syn::Fields::Named(f) = &s.fields {
                for field in &f.named {
                    if field.attrs.iter().any(|a| a.path().is_ident("xross_field")) {
                        let field_ident = field.ident.as_ref().unwrap();
                        let field_name = field_ident.to_string();
                        let xross_ty =
                            resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));
                                                fields.push(XrossField {
                                                    name: field_name.clone(),
                                                    ty: xross_ty.clone(),
                                                    safety: extract_safety_attr(&field.attrs, ThreadSafety::Lock),
                                                    docs: extract_docs(&field.attrs),
                                                });
                        
                                                match &xross_ty {
                                                    XrossType::String => {
                                                        let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, field_name);
                                                        let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, field_name);
                                                        extra_functions.push(quote! {
                                                            #[unsafe(no_mangle)]
                                                            pub unsafe extern "C" fn #get_fn(ptr: *const #name) -> *mut std::ffi::c_char {
                                                                if ptr.is_null() { return std::ptr::null_mut(); }
                                                                let obj = &*ptr;
                                                                std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                                                            }
                        
                                                            #[unsafe(no_mangle)]
                                                            pub unsafe extern "C" fn #set_fn(ptr: *mut #name, val: *const std::ffi::c_char) {
                                                                if ptr.is_null() || val.is_null() { return; }
                                                                let obj = &mut *ptr;
                                                                let s = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                                                                obj.#field_ident = s;
                                                            }
                                                        });
                                                    }
                                                    XrossType::Option(inner_xross) => {
                                                        let get_fn = format_ident!("{}_property_{}_opt_get", symbol_base, field_name);
                                                        let set_fn = format_ident!("{}_property_{}_opt_set", symbol_base, field_name);
                                                        let inner_rust_ty = extract_inner_type(&field.ty);
                                                        
                                                        let ok_val_logic = if matches!(**inner_xross, XrossType::String) {
                                                            quote! { std::ffi::CString::new(v.as_str()).unwrap().into_raw() as *mut std::ffi::c_void }
                                                        } else if inner_xross.is_owned() {
                                                            quote! { Box::into_raw(Box::new(v.clone())) as *mut std::ffi::c_void }
                                                        } else {
                                                            quote! { Box::into_raw(Box::new(*v)) as *mut std::ffi::c_void }
                                                        };
                        
                                                        extra_functions.push(quote! {
                                                            #[unsafe(no_mangle)]
                                                            pub unsafe extern "C" fn #get_fn(ptr: *const #name) -> *mut std::ffi::c_void {
                                                                if ptr.is_null() { return std::ptr::null_mut(); }
                                                                let obj = &*ptr;
                                                                match &obj.#field_ident {
                                                                    Some(v) => #ok_val_logic,
                                                                    None => std::ptr::null_mut(),
                                                                }
                                                            }
                        
                                                            #[unsafe(no_mangle)]
                                                            pub unsafe extern "C" fn #set_fn(ptr: *mut #name, val: *mut std::ffi::c_void) {
                                                                if ptr.is_null() { return; }
                                                                let obj = &mut *ptr;
                                                                obj.#field_ident = if val.is_null() {
                                                                    None
                                                                } else {
                                                                    Some(*Box::from_raw(val as *mut #inner_rust_ty))
                                                                };
                                                            }
                                                        });
                                                    }
                                                    XrossType::Result { ok: ok_xross, err: err_xross } => {
                                                        let get_fn = format_ident!("{}_property_{}_res_get", symbol_base, field_name);
                                                        
                                                        let gen_ptr = |ty: &XrossType, val_expr: proc_macro2::TokenStream| match ty {
                                                            XrossType::String => quote! {
                                                                std::ffi::CString::new(#val_expr.as_str()).unwrap().into_raw() as *mut std::ffi::c_void
                                                            },
                                                            _ => quote! {
                                                                Box::into_raw(Box::new(#val_expr.clone())) as *mut std::ffi::c_void
                                                            },
                                                        };
                                                        let ok_ptr_logic = gen_ptr(ok_xross, quote! { v });
                                                        let err_ptr_logic = gen_ptr(err_xross, quote! { e });
                        
                                                        extra_functions.push(quote! {
                                                            #[unsafe(no_mangle)]
                                                            pub unsafe extern "C" fn #get_fn(ptr: *const #name) -> xross_core::XrossResult {
                                                                if ptr.is_null() { return xross_core::XrossResult { ok_ptr: std::ptr::null_mut(), err_ptr: std::ptr::null_mut() }; }
                                                                let obj = &*ptr;
                                                                match &obj.#field_ident {
                                                                    Ok(v) => xross_core::XrossResult {
                                                                        ok_ptr: #ok_ptr_logic,
                                                                        err_ptr: std::ptr::null_mut(),
                                                                    },
                                                                    Err(e) => xross_core::XrossResult {
                                                                        ok_ptr: std::ptr::null_mut(),
                                                                        err_ptr: #err_ptr_logic,
                                                                    },
                                                                }
                                                            }
                                                        });
                                                    }
                                                    _ => {}
                                                }
                                            }
                        
                }
            }
            save_definition(&XrossDefinition::Struct(XrossStruct {
                signature: if package.is_empty() {
                    name.to_string()
                } else {
                    format!("{}.{}", package, name)
                },
                symbol_prefix: symbol_base.clone(),
                package_name: package,
                name: name.to_string(),
                fields,
                methods,
                docs: extract_docs(&s.attrs),
                is_copy: extract_is_copy(&s.attrs),
            }));

            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);
        }

        syn::Item::Enum(e) => {
            let name = &e.ident;
            let package = extract_package(&e.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            let layout_logic = generate_enum_layout(&e);

            let mut variants = Vec::new();
            let methods = vec![XrossMethod {
                name: "clone".to_string(),
                symbol: format!("{}_clone", symbol_base),
                method_type: XrossMethodType::ConstInstance,
                is_constructor: false,
                args: vec![],
                ret: XrossType::Object {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    ownership: Ownership::Owned,
                },
                safety: ThreadSafety::Lock,
                docs: vec!["Creates a clone of the native object.".to_string()],
            }];

            for v in &e.variants {
                let v_ident = &v.ident;
                let mut v_fields = Vec::new();
                let constructor_name = format_ident!("{}_new_{}", symbol_base, v_ident);

                let mut c_param_defs = Vec::new();
                let mut internal_conversions = Vec::new();
                let mut call_args = Vec::new();

                for (i, field) in v.fields.iter().enumerate() {
                    let field_name = field
                        .ident
                        .as_ref()
                        .map(|id| id.to_string())
                        .unwrap_or_else(|| i.to_string());
                    let ty = resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));

                    v_fields.push(XrossField {
                        name: field_name,
                        ty: ty.clone(),
                        safety: ThreadSafety::Lock,
                        docs: extract_docs(&field.attrs),
                    });

                    let arg_id = format_ident!("arg_{}", i);
                    let raw_ty = &field.ty;

                    if ty.is_owned() {
                        c_param_defs.push(quote! { #arg_id: *mut std::ffi::c_void });
                        if matches!(ty, XrossType::Object { ownership: Ownership::Boxed, .. }) {
                            internal_conversions
                                .push(quote! { let #arg_id = Box::from_raw(#arg_id as *mut _); });
                        } else {
                            internal_conversions.push(
                                quote! { let #arg_id = *Box::from_raw(#arg_id as *mut #raw_ty); },
                            );
                        }
                    } else if matches!(ty, XrossType::Object { .. }) {
                        c_param_defs.push(quote! { #arg_id: *mut std::ffi::c_void });
                        internal_conversions
                            .push(quote! { let #arg_id = &*(#arg_id as *const #raw_ty); });
                    } else {
                        c_param_defs.push(quote! { #arg_id: #raw_ty });
                    }
                    if let Some(id) = &field.ident {
                        call_args.push(quote! { #id: #arg_id });
                    } else {
                        call_args.push(quote! { #arg_id });
                    }
                }

                let enum_construct = if v.fields.is_empty() {
                    quote! { #name::#v_ident }
                } else if matches!(v.fields, syn::Fields::Named(_)) {
                    quote! { #name::#v_ident { #(#call_args),* } }
                } else {
                    quote! { #name::#v_ident(#(#call_args),*) }
                };

                extra_functions.push(quote! {
                    #[unsafe(no_mangle)]
                    pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #name {
                        #(#internal_conversions)*
                        Box::into_raw(Box::new(#enum_construct))
                    }
                });

                variants.push(XrossVariant {
                    name: v_ident.to_string(),
                    fields: v_fields,
                    docs: extract_docs(&v.attrs),
                });
            }

            save_definition(&XrossDefinition::Enum(XrossEnum {
                signature: if package.is_empty() {
                    name.to_string()
                } else {
                    format!("{}.{}", package, name)
                },
                symbol_prefix: symbol_base.clone(),
                package_name: package,
                name: name.to_string(),
                variants,
                methods,
                docs: extract_docs(&e.attrs),
                is_copy: extract_is_copy(&e.attrs),
            }));

            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);

            let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
            let variant_name_fn_id = format_ident!("{}_get_variant_name", symbol_base);
            let mut variant_arms = Vec::new();
            for v in &e.variants {
                let v_ident = &v.ident;
                let v_str = v_ident.to_string();
                variant_arms.push(quote! {
                    #name::#v_ident { .. } => #v_str,
                });
            }

            extra_functions.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #tag_fn_id(ptr: *const #name) -> i32 {
                    if ptr.is_null() { return -1; }
                    *(ptr as *const i32)
                }

                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #name) -> *mut std::ffi::c_char {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let val = &*ptr;
                    let name = match val {
                        #(#variant_arms)*
                    };
                    std::ffi::CString::new(name).unwrap().into_raw()
                }
            });
        }
        _ => panic!("#[derive(XrossClass)] only supports Struct and Enum"),
    }

    quote!(#(#extra_functions)*)
}
