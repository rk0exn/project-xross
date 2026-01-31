extern crate proc_macro;

use proc_macro::TokenStream;
use quote::{quote, format_ident};
use syn::{
    parse_macro_input,
    DeriveInput,
    Attribute,
    Meta,
    Data,
    Fields,
    Type,
    Visibility,
    ItemImpl,
    ImplItem,
    punctuated::Punctuated,
    Token,
};
use serde::{Serialize, Deserialize};
use std::{fs, path::PathBuf};

// --- ヘルパー関数 ---

fn has_derive_attribute(attrs: &[Attribute], trait_name: &str) -> bool {
    attrs.iter().any(|attr| {
        if attr.path().is_ident("derive") {
            let mut found = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident(trait_name) {
                    found = true;
                }
                Ok(())
            });
            found
        } else {
            false
        }
    })
}

fn is_primitive_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            matches!(
                ident_str.as_str(),
                "u8" | "i8" | "u16" | "i16" | "u32" | "i32" | "u64" | "i64" | "f32" | "f64" | "bool"
            )
        } else { false }
    } else { false }
}

fn is_string_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            segment.ident == "String" && segment.arguments.is_empty()
        } else { false }
    } else { false }
}

fn is_jvm_class_type(ty: &Type) -> bool {
    if let Type::Path(type_path) = ty {
        if let Some(segment) = type_path.path.segments.last() {
            let ident_str = segment.ident.to_string();
            !(is_primitive_type(ty) || is_string_type(ty) || ident_str == "Option" || ident_str == "Vec")
        } else { false }
    } else { false }
}

fn generate_ffi_prefix(crate_name: &str, struct_name_str: &str) -> String {
    // 注: module_path!() はコンパイル時のトークンなため、マクロ内での取得には制限があります。
    // ここでは簡易的に crate_name と struct_name を結合します。
    format!("{}_{}", crate_name, struct_name_str)
}

// --- メタデータ定義 ---

#[derive(Debug, Serialize, Deserialize)]
struct FieldMetadata {
    pub name: String,
    pub rust_type: String,
    pub ffi_getter_name: String,
    pub ffi_setter_name: String,
    pub ffi_type: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct MethodMetadata {
    pub name: String,
    pub ffi_name: String,
    pub args: Vec<String>,
    pub return_type: String,
    pub has_self: bool,
    pub is_static: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct StructMetadata {
    pub name: String,
    pub ffi_prefix: String,
    pub new_fn_name: String,
    pub drop_fn_name: String,
    pub clone_fn_name: String,
    pub fields: Vec<FieldMetadata>,
    pub methods: Vec<MethodMetadata>,
}

// --- メインマクロ ---

#[proc_macro_derive(JvmClass, attributes(jvm_class))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let ast = parse_macro_input!(input as DeriveInput);
    let name = &ast.ident;
    let name_str = name.to_string();

    let mut crate_name_opt: Option<String> = None;

    // #[jvm_class(crate = "name")] のパース (syn 2.0)
    for attr in &ast.attrs {
        if attr.path().is_ident("jvm_class") {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("crate") {
                    let value = meta.value()?;
                    let s: syn::LitStr = value.parse()?;
                    crate_name_opt = Some(s.value());
                }
                Ok(())
            });
        }
    }

    let crate_name = match crate_name_opt {
        Some(c) => c,
        None => return quote! { compile_error!("Missing #[jvm_class(crate = \"...\")]"); }.into(),
    };

    if !ast.attrs.iter().any(|attr| attr.path().is_ident("repr")) {
        // 簡易チェック。本来は repr(C) まで見るべきですが、一旦存在確認
    }

    let ffi_prefix = generate_ffi_prefix(&crate_name, &name_str);
    let new_fn_name = format_ident!("{}_new", ffi_prefix);
    let drop_fn_name = format_ident!("{}_drop", ffi_prefix);
    let clone_fn_name = format_ident!("{}_clone", ffi_prefix);

    let mut getter_setter_fns = quote! {};
    let mut field_metadata_list = Vec::new();

    if let Data::Struct(data_struct) = &ast.data {
        if let Fields::Named(fields) = &data_struct.fields {
            for field in &fields.named {
                if let Visibility::Public(_) = &field.vis {
                    let field_name = field.ident.as_ref().unwrap();
                    let field_name_str = field_name.to_string();
                    let field_type = &field.ty;
                    let field_type_str = quote! { #field_type }.to_string().replace(" ", "");

                    let getter_name = format_ident!("{}_get_{}", ffi_prefix, field_name_str);
                    let setter_name = format_ident!("{}_set_{}", ffi_prefix, field_name_str);

                    let mut ffi_type_label = String::new();

                    if is_primitive_type(field_type) {
                        ffi_type_label = field_type_str.clone();
                        getter_setter_fns.extend(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> #field_type {
                                unsafe { (*ptr).#field_name }
                            }
                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: #field_type) {
                                unsafe { (*ptr).#field_name = value; }
                            }
                        });
                    } else if is_string_type(field_type) {
                        ffi_type_label = "*const libc::c_char".to_string();
                        getter_setter_fns.extend(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> *const libc::c_char {
                                unsafe {
                                    let s = &(*ptr).#field_name;
                                    let c_str = std::ffi::CString::new(s.as_str()).unwrap();
                                    c_str.into_raw()
                                }
                            }
                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: *const libc::c_char) {
                                unsafe {
                                    let c_str = std::ffi::CStr::from_ptr(value);
                                    (*ptr).#field_name = c_str.to_string_lossy().into_owned();
                                }
                            }
                        });
                    } else if is_jvm_class_type(field_type) {
                        ffi_type_label = format!("*mut {}", field_type_str);
                        getter_setter_fns.extend(quote! {
                            #[no_mangle]
                            pub extern "C" fn #getter_name(ptr: *const #name) -> *mut #field_type {
                                unsafe { Box::into_raw(Box::new((*ptr).#field_name.clone())) }
                            }
                            #[no_mangle]
                            pub extern "C" fn #setter_name(ptr: *mut #name, value: *mut #field_type) {
                                unsafe { (*ptr).#field_name = *Box::from_raw(value); }
                            }
                        });
                    }

                    field_metadata_list.push(FieldMetadata {
                        name: field_name_str,
                        rust_type: field_type_str,
                        ffi_getter_name: getter_name.to_string(),
                        ffi_setter_name: setter_name.to_string(),
                        ffi_type: ffi_type_label,
                    });
                }
            }
        }
    }

    // メタデータ保存
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let metadata = StructMetadata {
            name: name_str,
            ffi_prefix: ffi_prefix.clone(),
            new_fn_name: new_fn_name.to_string(),
            drop_fn_name: drop_fn_name.to_string(),
            clone_fn_name: clone_fn_name.to_string(),
            fields: field_metadata_list,
            methods: Vec::new(),
        };
        let path = PathBuf::from(out_dir).join(format!("{}_struct_metadata.json", ffi_prefix));
        let _ = fs::write(path, serde_json::to_string_pretty(&metadata).unwrap());
    }

    quote! {
        impl xross_core::JvmClassTrait for #name {
            fn new() -> Self { Self::default() }
        }
        #[no_mangle]
        pub extern "C" fn #new_fn_name() -> *mut #name { Box::into_raw(Box::new(#name::new())) }
        #[no_mangle]
        pub extern "C" fn #drop_fn_name(ptr: *mut #name) { if !ptr.is_null() { unsafe { drop(Box::from_raw(ptr)); } } }
        #[no_mangle]
        pub extern "C" fn #clone_fn_name(ptr: *const #name) -> *mut #name {
            unsafe { Box::into_raw(Box::new((*ptr).clone())) }
        }
        #getter_setter_fns
    }.into()
}

#[proc_macro_attribute]
pub fn jvm_impl(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut crate_name_opt: Option<String> = None;

    // 引数のパース #[jvm_impl(crate = "...")]
    let attr_parser = syn::meta::parser(|meta| {
        if meta.path.is_ident("crate") {
            let value = meta.value()?;
            let s: syn::LitStr = value.parse()?;
            crate_name_opt = Some(s.value());
            Ok(())
        } else {
            Err(meta.error("unsupported attribute"))
        }
    });
    parse_macro_input!(attr with attr_parser);

    let crate_name = crate_name_opt.expect("jvm_impl requires crate name");
    let mut ast = parse_macro_input!(item as ItemImpl);
    let self_ty = &ast.self_ty;
    let self_ty_str = quote!{ #self_ty }.to_string().replace(" ", "");
    let ffi_prefix = generate_ffi_prefix(&crate_name, &self_ty_str);

    let mut generated_fns = quote! {};
    let mut method_metadata_list = Vec::new();

    for item in &mut ast.items {
        if let ImplItem::Fn(method) = item {
            if let Visibility::Public(_) = &method.vis {
                let method_name = &method.sig.ident;
                let ffi_method_name = format_ident!("{}_impl_{}", ffi_prefix, method_name);

                method_metadata_list.push(MethodMetadata {
                    name: method_name.to_string(),
                    ffi_name: ffi_method_name.to_string(),
                    args: vec![], // 簡易化
                    return_type: "()".to_string(),
                    has_self: method.sig.receiver().is_some(),
                    is_static: method.sig.receiver().is_none(),
                });

                generated_fns.extend(quote! {
                    #[no_mangle]
                    pub extern "C" fn #ffi_method_name() {
                        unimplemented!("Method wrap not fully implemented");
                    }
                });
            }
        }
    }

    // メタデータ保存
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir).join(format!("{}_method_metadata.json", ffi_prefix));
        let _ = fs::write(path, serde_json::to_string_pretty(&method_metadata_list).unwrap());
    }

    quote! {
        #ast
        #generated_fns
    }.into()
}
