use proc_macro::TokenStream;
use quote::{format_ident, quote};
use std::fs;
use std::path::Path;
use syn::{
    DeriveInput, Expr, ExprLit, FnArg, ImplItem, ItemImpl, Lit, Meta, Pat, Type, parse_macro_input,
};
use xross_metadata::{XrossClass, XrossField, XrossMethod, XrossMethodType, XrossType};

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

#[proc_macro_derive(JvmClass)]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as DeriveInput);
    let struct_name = &input.ident;
    let mut fields_meta = Vec::new();

    if let syn::Data::Struct(data) = &input.data {
        for field in &data.fields {
            let field_name = field
                .ident
                .as_ref()
                .map(|i| i.to_string())
                .unwrap_or_default();
            let field_docs = extract_docs(&field.attrs);
            let ty = map_type(&field.ty);
            fields_meta.push(XrossField {
                name: field_name,
                ty,
                docs: field_docs,
            });
        }
    }

    // ここで一旦中間のメタデータを保存するか、静的変数に保持する設計が必要ですが、
    // Rustマクロの制約上、jvm_export_impl 側で完結させるのがクリーンです。
    // そのため、derive側では共通関数生成のみに専念します。

    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_default()
        .replace("-", "_");
    let fn_drop = format_ident!("{}_{}_drop", crate_name, struct_name);

    quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #fn_drop(ptr: *mut #struct_name) {
            if !ptr.is_null() { let _ = Box::from_raw(ptr); }
        }
        impl #struct_name {
            pub const JVM_STRUCT_NAME: &'static str = stringify!(#struct_name);
        }
    }
    .into()
}

#[proc_macro_attribute]
pub fn jvm_export_impl(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_default()
        .replace("-", "_");

    // 属性からパッケージ名を取得（なければcrate名）
    let attr_str = attr.to_string().replace(" ", "").replace("\"", "");
    let package_name = if attr_str.is_empty() {
        crate_name.clone()
    } else {
        attr_str
    };

    let struct_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("Direct struct name required");
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let method_docs = extract_docs(&method.attrs);

            // 属性のフラグ
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
                // シンボル名は重複を避けるためパッケージ名も含めて正規化
                let symbol_name = format!(
                    "{}_{}_{}",
                    package_name.replace(".", "_"),
                    struct_name_ident,
                    rust_fn_name
                );
                let export_ident = format_ident!("{}", symbol_name);

                let mut method_type = XrossMethodType::Static;
                let mut args_meta = Vec::new();

                // 引数解析
                for input in &method.sig.inputs {
                    match input {
                        FnArg::Receiver(receiver) => {
                            method_type = if receiver.reference.is_none() {
                                XrossMethodType::OwnedInstance
                            } else if receiver.mutability.is_some() {
                                XrossMethodType::MutInstance
                            } else {
                                XrossMethodType::ConstInstance
                            };
                        }
                        FnArg::Typed(pat_type) => {
                            let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                                id.ident.to_string()
                            } else {
                                "unknown".to_string()
                            };

                            // 型判定の高度化（実際はもっと詳細なマッチングが必要）
                            let ty = map_type(&pat_type.ty);
                            args_meta.push(XrossField {
                                name: arg_name,
                                ty,
                                docs: vec![],
                            });
                        }
                    }
                }
                // 戻り値の解析
                let ret = if is_new {
                    XrossType::Pointer // コンストラクタの場合は強制的にポインタ
                } else {
                    match &method.sig.output {
                        syn::ReturnType::Default => XrossType::Void, // 戻り値なし "->" がない場合
                        syn::ReturnType::Type(_, ty) => map_type(ty), // "-> T" の T を解析
                    }
                };

                methods_meta.push(XrossMethod {
                    name: rust_fn_name.to_string(),
                    symbol: symbol_name,
                    method_type,
                    is_constructor: is_new,
                    args: args_meta,
                    ret, // 解析した結果を入れる
                    docs: method_docs,
                });

                // --- ラッパー関数生成 ---
                let mut c_args = Vec::new();
                let mut call_args = Vec::new();

                for input in &method.sig.inputs {
                    match input {
                        FnArg::Receiver(receiver) => {
                            let arg_ident = format_ident!("_self");
                            if receiver.reference.is_none() {
                                // self: インスタンスを消費
                                c_args.push(quote! { #arg_ident: *mut #struct_name_ident });
                                call_args.push(quote! { *Box::from_raw(#arg_ident) });
                            } else if receiver.mutability.is_some() {
                                // &mut self
                                c_args.push(quote! { #arg_ident: *mut #struct_name_ident });
                                call_args.push(quote! { &mut *#arg_ident });
                            } else {
                                // &self
                                c_args.push(quote! { #arg_ident: *const #struct_name_ident });
                                call_args.push(quote! { &*#arg_ident });
                            }
                        }
                        FnArg::Typed(pat_type) => {
                            c_args.push(quote! { #pat_type });
                            if let Pat::Ident(id) = &*pat_type.pat {
                                let name = &id.ident;
                                call_args.push(quote! { #name });
                            }
                        }
                    }
                }

                let wrapper = if is_new {
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> *mut #struct_name_ident {
                            Box::into_raw(Box::new(#struct_name_ident::#rust_fn_name(#(#call_args),*)))
                        }
                    }
                } else {
                    let ret = &method.sig.output;
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#(#c_args),*) #ret {
                            #struct_name_ident::#rust_fn_name(#(#call_args),*)
                        }
                    }
                };
                extra_functions.push(wrapper);
            }
        }
    }

    // JSON書き出し（ディレクトリ分割）
    let class_meta = XrossClass {
        package_name: package_name.clone(),
        struct_name: struct_name_ident.to_string(),
        docs: vec![],
        fields: vec![],
        methods: methods_meta,
    };

    // パッケージ名をパスに変換 (org.xross -> target/xross/org/xross/)
    let package_path = package_name.replace(".", "/");
    let target_dir = Path::new("target/xross").join(package_path);
    fs::create_dir_all(&target_dir).ok();

    if let Ok(json) = serde_json::to_string_pretty(&class_meta) {
        fs::write(target_dir.join(format!("{}.json", struct_name_ident)), json).ok();
    }

    quote! {
    #input_impl
    #(#extra_functions)* }
    .into()
}
