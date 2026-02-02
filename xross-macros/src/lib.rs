mod type_mapping;
mod utils;

use heck::ToSnakeCase;
use proc_macro::TokenStream;
use quote::{format_ident, quote};
use std::fs;
use std::path::Path;
use syn::{FnArg, ImplItem, ItemImpl, ItemStruct, Pat, ReturnType, Type, parse_macro_input};
use type_mapping::map_type;
use utils::*;
use xross_metadata::{XrossClass, XrossField, XrossMethod, XrossMethodType, XrossType};

#[proc_macro_derive(JvmClass, attributes(jvm_field))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as ItemStruct);
    let struct_name = &input.ident;
    let mut fields_meta = Vec::new();
    let mut layout_parts = Vec::new();

    if let syn::Fields::Named(named_fields) = &input.fields {
        for field in &named_fields.named {
            if field.attrs.iter().any(|a| a.path().is_ident("jvm_field")) {
                let f_ident = field.ident.as_ref().unwrap();
                let ty = map_type(&field.ty);
                fields_meta.push(XrossField {
                    name: f_ident.to_string(),
                    ty: ty.clone(),
                    docs: extract_docs(&field.attrs),
                });
                let field_type = &field.ty;
                let f_name = f_ident.to_string();
                layout_parts.push(quote! {
                    format!("{}:{}:{}", #f_name, std::mem::offset_of!(#struct_name, #f_ident), std::mem::size_of::<#field_type>())
                });
            }
        }
    }

    save_struct_metadata(struct_name, &fields_meta, &extract_docs(&input.attrs));
    let marker_name = format_ident!("XrossJvmMarker{}", struct_name);

    quote! {
        pub trait #marker_name { fn layout() -> *mut std::ffi::c_char; }
        impl #marker_name for #struct_name {
            fn layout() -> *mut std::ffi::c_char {
                let mut parts = vec![format!("__self:0:{}", std::mem::size_of::<#struct_name>())];
                #( parts.push(#layout_parts); )*
                let joined = parts.join(";");
                std::ffi::CString::new(joined).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
            }
        }
    }
    .into()
}

#[proc_macro_attribute]
pub fn jvm_class(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);
    let crate_name = env!("CARGO_PKG_NAME").replace("-", "_");

    let struct_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("Direct struct name required for jvm_class");
    };

    let package_name = attr.to_string().replace(" ", "").replace("\"", "");
    let symbol_base = format!(
        "{}{}_{}",
        crate_name,
        if !package_name.is_empty() {
            format!("_{}", package_name.replace(".", "_"))
        } else {
            "".to_string()
        },
        struct_name_ident.to_string().to_snake_case()
    );

    let (struct_docs, struct_fields_meta) = load_struct_metadata(struct_name_ident);
    let mut methods_meta = Vec::new();
    let mut extra_functions = Vec::new();

    // 基本エクスポート関数の生成 (drop, clone, layout)
    let drop_ident = format_ident!("{}_drop", symbol_base);
    let clone_ident = format_ident!("{}_clone", symbol_base);
    let layout_export_ident = format_ident!("{}_layout", symbol_base);
    let ref_ident = format_ident!("{}_ref", symbol_base);
    let ref_mut_ident = format_ident!("{}_refMut", symbol_base);
    let marker_name = format_ident!("XrossJvmMarker{}", struct_name_ident);

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_ident(ptr: *mut #struct_name_ident) {
            if !ptr.is_null() { unsafe { let _ = Box::from_raw(ptr); } }
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #clone_ident(ptr: *const #struct_name_ident) -> *mut #struct_name_ident {
            if ptr.is_null() { return std::ptr::null_mut(); }
            unsafe { Box::into_raw(Box::new((*ptr).clone())) }
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_export_ident() -> *mut std::ffi::c_char {
            unsafe { <#struct_name_ident as #marker_name>::layout() }
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #ref_ident(ptr: *const #struct_name_ident) -> *const std::ffi::c_void {
            ptr as *const std::ffi::c_void
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #ref_mut_ident(ptr: *mut #struct_name_ident) -> *mut std::ffi::c_void {
            ptr as *mut std::ffi::c_void
        }
    });

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;
            method.attrs.retain(|attr| {
                if attr.path().is_ident("jvm_new") {
                    is_new = true;
                    false // retain(false) で元の impl ブロックから削除
                } else if attr.path().is_ident("jvm_method") {
                    is_method = true;
                    false // 同様に削除
                } else {
                    true // その他の属性（#[doc] など）は残す
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
                            c_args.push(quote! { #arg_ident: *mut #struct_name_ident });
                            call_args.push(quote! { *Box::from_raw(#arg_ident) });
                        } else if receiver.mutability.is_some() {
                            method_type = XrossMethodType::MutInstance;
                            c_args.push(quote! { #arg_ident: *mut #struct_name_ident });
                            call_args.push(quote! { &mut *#arg_ident });
                        } else {
                            method_type = XrossMethodType::ConstInstance;
                            c_args.push(quote! { #arg_ident: *const #struct_name_ident });
                            call_args.push(quote! { &*#arg_ident });
                        }
                    }
                    FnArg::Typed(pat_type) => {
                        let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                            id.ident.to_string()
                        } else {
                            "arg".into()
                        };
                        let arg_ident = format_ident!("{}", arg_name);
                        let xross_ty = map_type(&pat_type.ty);

                        args_meta.push(XrossField {
                            name: arg_name.clone(),
                            ty: xross_ty.clone(),
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
                                call_args.push(quote! { #arg_ident });
                            }
                            _ => {
                                c_args.push(quote! { #pat_type });
                                call_args.push(quote! { #arg_ident });
                            }
                        }
                    }
                }
            }

            let ret_ty = if is_new {
                XrossType::Pointer
            } else {
                match &method.sig.output {
                    ReturnType::Default => XrossType::Void,
                    ReturnType::Type(_, ty) => map_type(ty),
                }
            };

            methods_meta.push(XrossMethod {
                name: rust_fn_name.to_string(),
                symbol: symbol_name.clone(),
                method_type,
                is_constructor: is_new,
                args: args_meta,
                ret: ret_ty.clone(),
                docs: extract_docs(&method.attrs),
            });

            // C互換の戻り値の型を定義
            let c_ret_type = match &ret_ty {
                XrossType::Void => quote! { () },
                XrossType::String => quote! { *mut std::ffi::c_char },
                XrossType::Pointer => quote! { *mut std::ffi::c_void },
                XrossType::Struct { .. } => quote! { *mut std::ffi::c_void },
                _ => {
                    // プリミティブ型 (i32, f64等) は元の型をそのまま使用
                    if let ReturnType::Type(_, ty) = &method.sig.output {
                        quote! { #ty }
                    } else {
                        quote! { () }
                    }
                }
            };

            // ラッパー関数のボディ (変換ロジックを含む)
            let inner_call = quote! { #struct_name_ident::#rust_fn_name(#(#call_args),*) };
            let wrapper_body = if is_new {
                quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void }
            } else {
                match &ret_ty {
                    XrossType::String => {
                        quote! { std::ffi::CString::new(#inner_call).unwrap_or_default().into_raw() }
                    }
                    XrossType::Struct { is_reference, .. } => {
                        if *is_reference {
                            // &T を返す場合はポインタをそのまま渡す
                            quote! { #inner_call as *const _ as *mut std::ffi::c_void }
                        } else {
                            // T (Owned) を返す場合はBoxに入れて所有権をJavaに渡す
                            quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void }
                        }
                    }
                    XrossType::Void => quote! { #inner_call },
                    _ => quote! { #inner_call }, // プリミティブ
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

    // メタデータのJSON出力
    let class_meta = XrossClass {
        package_name: package_name.clone(),
        symbol_prefix: symbol_base,
        struct_name: struct_name_ident.to_string(),
        docs: struct_docs,
        fields: struct_fields_meta,
        methods: methods_meta,
    };
    let target_dir = Path::new("target/xross").join(package_name.replace(".", "/"));
    fs::create_dir_all(&target_dir).ok();
    if let Ok(json) = serde_json::to_string_pretty(&class_meta) {
        fs::write(target_dir.join(format!("{}.json", struct_name_ident)), json).ok();
    }

    quote! {
        #input_impl
        #(#extra_functions)*
    }
    .into()
}
