use heck::ToSnakeCase;
use proc_macro::TokenStream;
use quote::{format_ident, quote};
use regex::Regex;
use std::fs;
use std::path::{Path, PathBuf};
use syn::{Expr, ExprLit, FnArg, ImplItem, ItemImpl, Lit, Meta, Pat, Type, parse_macro_input};
use xross_metadata::{XrossClass, XrossField, XrossMethod, XrossMethodType, XrossType};

fn get_tmp_path(struct_name: &syn::Ident) -> PathBuf {
    Path::new("target/xross/tmp").join(format!("{}_fields.json", struct_name))
}

fn save_struct_metadata(struct_name: &syn::Ident, fields: &Vec<XrossField>, docs: &Vec<String>) {
    let tmp_dir = Path::new("target/xross/tmp");
    fs::create_dir_all(tmp_dir).ok();

    let data = serde_json::json!({
        "fields": fields,
        "docs": docs,
    });

    if let Ok(json) = serde_json::to_string(&data) {
        fs::write(get_tmp_path(struct_name), json).ok();
    }
}
fn load_struct_metadata(struct_name: &syn::Ident) -> (Vec<String>, Vec<XrossField>) {
    let path = get_tmp_path(struct_name);
    if let Ok(content) = fs::read_to_string(&path) {
        let _ = fs::remove_file(&path);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&content) {
            let docs = serde_json::from_value(val["docs"].clone()).unwrap_or_default();
            let fields = serde_json::from_value(val["fields"].clone()).unwrap_or_default();
            return (docs, fields);
        }
    }
    (vec![], vec![])
}

// 型判定の補助関数
fn map_type(ty: &Type) -> XrossType {
    let ty_str = quote!(#ty).to_string().replace(" ", "");
    match ty_str.as_str() {
        "i8" => XrossType::I8,
        "i16" => XrossType::I16,
        "i32" | "std::primitive::i32" => XrossType::I32,
        "i64" | "std::primitive::i64" => XrossType::I64,
        "f32" | "std::primitive::f32" => XrossType::F32,
        "f64" | "std::primitive::f64" => XrossType::F64,
        "bool" => XrossType::Bool,
        "String" | "&str" => XrossType::String,
        "u16" => XrossType::U16,
        s if s.contains("MemorySegment") || s.contains("Pointer") => XrossType::Pointer,
        _ => XrossType::Pointer,
    }
}
// ヘルパー：属性からdocコメントを抽出する
fn extract_docs(attrs: &[syn::Attribute]) -> Vec<String> {
    attrs
        .iter()
        .filter(|a| a.path().is_ident("doc"))
        .filter_map(|a| {
            if let Meta::NameValue(nv) = &a.meta {
                if let Expr::Lit(ExprLit {
                    lit: Lit::Str(s), ..
                }) = &nv.value
                {
                    return Some(s.value().trim().to_string());
                }
            }
            None
        })
        .collect()
}

#[proc_macro_derive(JvmClass, attributes(jvm_field))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as syn::ItemStruct);
    let struct_name = &input.ident;

    let mut fields_meta = Vec::new();
    let mut layout_parts = Vec::new();

    if let syn::Fields::Named(named_fields) = &input.fields {
        for field in &named_fields.named {
            if field.attrs.iter().any(|a| a.path().is_ident("jvm_field")) {
                let f_ident = field.ident.as_ref().unwrap();
                let f_name = f_ident.to_string();

                fields_meta.push(XrossField {
                    name: f_name.clone(),
                    ty: map_type(&field.ty),
                    docs: extract_docs(&field.attrs),
                });

                // フィールドごとの情報を構築する quote
                // ptr は layout 関数内で定義される MaybeUninit のポインタを利用
                // フィールドの型を取得
                let field_type = &field.ty;

                // layout_parts の構築
                layout_parts.push(quote! {
                    format!(
                        "{}:{}:{}",
                        #f_name,
                        // フィールドのオフセット
                        std::mem::offset_of!(#struct_name, #f_ident),
                        // フィールドの型から直接サイズを取得（ptr不要・安全）
                        std::mem::size_of::<#field_type>()
                    )
                });
            }
        }
    }

    save_struct_metadata(struct_name, &fields_meta, &extract_docs(&input.attrs));

    let marker_name = format_ident!("XrossJvmMarker{}", struct_name);

    quote! {
        #[allow(unused)]
        pub trait #marker_name {
            fn layout() -> *mut std::ffi::c_char;
        }
        impl #marker_name for #struct_name {
            fn layout() -> *mut std::ffi::c_char {
                let mut parts = Vec::new();

                // 1. 構造体全体の情報 (名前:サイズ:アライメント)
                parts.push(format!("{}:{}:{}",
                    stringify!(#struct_name),
                    std::mem::size_of::<#struct_name>(),
                    std::mem::align_of::<#struct_name>()
                ));

                // 2. フィールド情報の追加 (ここで先ほどの layout_parts が展開される)
                #(
                    parts.push(#layout_parts);
                )*

                let joined = parts.join(";");

                match std::ffi::CString::new(joined) {
                    Ok(c_str) => c_str.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                }
            }
        }
    }
    .into()
}

#[proc_macro_attribute]
pub fn jvm_class(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);
    let crate_name = env!("CARGO_PKG_NAME").replace("-", "_");

    // パッケージ名の解析
    let package_name = {
        let raw_attr = attr.to_string().replace(" ", "").replace("\"", "");
        let re = Regex::new(r"^[a-zA-Z0-9._]*$").unwrap();
        if !re.is_match(&raw_attr) && !raw_attr.is_empty() {
            return syn::Error::new_spanned(
                &input_impl.self_ty,
                format!("Invalid package name '{}': Only alphanumeric, dots, and underscores are allowed.", raw_attr),
            ).to_compile_error().into();
        }
        raw_attr
    };

    // 構造体名の取得
    let struct_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        return syn::Error::new_spanned(&input_impl.self_ty, "Direct struct name required")
            .to_compile_error()
            .into();
    };

    // シンボルベース名
    let symbol_base = format!(
        "{}{}_{}",
        crate_name,
        if !package_name.is_empty() {
            format!("_{}", package_name.replace('.', "_"))
        } else {
            "".into()
        },
        struct_name_ident.to_string().to_snake_case()
    );

    // --- メタデータの同期 ---
    // derive(JvmClass) が書き出したフィールド/レイアウト情報をロード
    let (struct_docs, struct_fields_meta) = load_struct_metadata(struct_name_ident);

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();
    // jvm_class 属性マクロ内
    let drop_ident = format_ident!("{}_drop", symbol_base);
    let clone_ident = format_ident!("{}_clone", symbol_base);
    let layout_export_ident = format_ident!("{}_layout", symbol_base);

    // derive 側で生成されるトレイト名をここでも生成して一致させる
    let marker_name = format_ident!("XrossJvmMarker{}", struct_name_ident);

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_ident(ptr: *mut #struct_name_ident) {
            if !ptr.is_null() {
                unsafe{
                    let _ = Box::from_raw(ptr);
                }
            }
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #clone_ident(ptr: *const #struct_name_ident) -> *mut #struct_name_ident {
            if ptr.is_null() { return std::ptr::null_mut(); }
            unsafe{
                Box::into_raw(Box::new((*ptr).clone()))
            }
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_export_ident() -> *mut std::ffi::c_char {
            unsafe{
                <#struct_name_ident as #marker_name>::layout()
            }
        }
    });
    // メソッド解析ループ
    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let mut is_new = false;
            let mut is_method = false;

            method.attrs.retain(|a| {
                if a.path().is_ident("jvm_new") {
                    is_new = true;
                    false
                } else if a.path().is_ident("jvm_method") {
                    is_method = true;
                    false
                } else {
                    true
                }
            });

            if is_new || is_method {
                let rust_fn_name = &method.sig.ident;
                let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
                let export_ident = format_ident!("{}", symbol_name);
                let mut method_type = XrossMethodType::Static;
                let mut args_meta = Vec::new();

                // 引数解析 & ラッパー引数構築
                let mut c_args = Vec::new();
                let mut call_args = Vec::new();

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
                                "unknown".into()
                            };

                            args_meta.push(XrossField {
                                name: arg_name,
                                ty: map_type(&pat_type.ty),
                                docs: vec![],
                            });

                            c_args.push(quote! { #pat_type });
                            if let Pat::Ident(id) = &*pat_type.pat {
                                let name = &id.ident;
                                call_args.push(quote! { #name });
                            }
                        }
                    }
                }

                let ret_ty = if is_new {
                    XrossType::Pointer
                } else {
                    match &method.sig.output {
                        syn::ReturnType::Default => XrossType::Void,
                        syn::ReturnType::Type(_, ty) => map_type(ty),
                    }
                };

                methods_meta.push(XrossMethod {
                    name: rust_fn_name.to_string(),
                    symbol: symbol_name,
                    method_type,
                    is_constructor: is_new,
                    args: args_meta,
                    ret: ret_ty,
                    docs: extract_docs(&method.attrs),
                });

                let wrapper = if is_new {
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> *mut #struct_name_ident {
                            Box::into_raw(Box::new(#struct_name_ident::#rust_fn_name(#(#call_args),*)))
                        }
                    }
                } else {
                    let ret_sig = &method.sig.output;
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#(#c_args),*) #ret_sig {
                            #struct_name_ident::#rust_fn_name(#(#call_args),*)
                        }
                    }
                };
                extra_functions.push(wrapper);
            }
        }
    }

    // 最終メタデータの書き出し
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

    // --- チェック用マーカー ---
    // derive(JvmClass) が同じ名前のトレイトを生成することを期待
    let marker_name = format_ident!("XrossJvmMarker{}", struct_name_ident);

    quote! {
        #input_impl

        // implブロックの後に配置して確実に解決させる
        const _: fn() = || {
            fn assert_impls_jvm_class<T: #marker_name>() {}
            assert_impls_jvm_class::<#struct_name_ident>();
        };

        #(#extra_functions)*
    }
    .into()
}
