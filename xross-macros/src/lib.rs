use proc_macro::TokenStream;
use quote::{format_ident, quote};
use std::fs;
use std::path::Path;
use syn::{
    DeriveInput, Expr, ExprLit, FnArg, ImplItem, ItemImpl, Lit, Meta, Type, parse_macro_input,
};
use xross_metadata::{XrossClass, XrossField, XrossMethod, XrossType};

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
            // 型判定 (簡易)
            let ty_str = quote!(#field.ty).to_string();
            let ty = if ty_str.contains("i32") {
                XrossType::I32
            } else {
                XrossType::Pointer
            };

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

    let attr_str = attr.to_string().replace(" ", "");
    let package_name = if attr_str.is_empty() {
        crate_name.clone()
    } else {
        attr_str.clone()
    };
    let prefix = format!("{}_{}", crate_name, attr_str.replace(".", "_"))
        .trim_end_matches('_')
        .to_string();

    let struct_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("Required direct struct name");
    };

    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    for item in &mut input_impl.items {
        if let ImplItem::Fn(method) = item {
            let method_docs = extract_docs(&method.attrs);
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
                let export_ident =
                    format_ident!("{}_{}_{}", prefix, struct_name_ident, rust_fn_name);

                // 型解析
                let mut args_types = Vec::new();
                for input in &method.sig.inputs {
                    match input {
                        FnArg::Receiver(_) => args_types.push(XrossType::Pointer),
                        _ => args_types.push(XrossType::I32), // 簡略化
                    }
                }

                methods_meta.push(XrossMethod {
                    name: rust_fn_name.to_string(),
                    symbol: export_ident.to_string(),
                    is_constructor: is_new,
                    args: args_types,
                    ret: if is_new {
                        XrossType::Pointer
                    } else {
                        XrossType::I32
                    },
                    docs: method_docs,
                });

                // --- Rustラッパー生成ロジック (既存維持) ---
                let mut c_args = method.sig.inputs.clone();
                let mut call_args = Vec::new();
                let self_ptr_ident = format_ident!("self_ptr");

                for arg in c_args.iter_mut() {
                    match arg {
                        FnArg::Receiver(receiver) => {
                            let mutability = &receiver.mutability;
                            call_args.push(quote! { & #mutability *#self_ptr_ident });
                            let ptr_modifier = if mutability.is_some() {
                                quote! { mut }
                            } else {
                                quote! { const }
                            };
                            *arg = syn::parse_quote!(#self_ptr_ident: *#ptr_modifier #struct_name_ident);
                        }
                        FnArg::Typed(pat_type) => {
                            if let syn::Pat::Ident(id) = &*pat_type.pat {
                                let name = id.ident.clone();
                                call_args.push(quote! { #name });
                            } else {
                                panic!("Unsupported argument pattern");
                            }
                        }
                    }
                }

                let wrapper = if is_new {
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#c_args) -> *mut #struct_name_ident {
                            Box::into_raw(Box::new(#struct_name_ident::#rust_fn_name(#(#call_args),*)))
                        }
                    }
                } else {
                    let ret = &method.sig.output;
                    quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #export_ident(#c_args) #ret {
                            #struct_name_ident::#rust_fn_name(#(#call_args),*)
                        }
                    }
                };
                extra_functions.push(wrapper);
            }
        }
    }

    // JSON書き出し
    let class_meta = XrossClass {
        package_name: package_name,
        struct_name: struct_name_ident.to_string(),
        docs: vec![], // implブロックからは取りづらいためderiveと合わせるのが理想
        fields: vec![],
        methods: methods_meta,
    };

    let target_dir = Path::new("target/xross");
    fs::create_dir_all(target_dir).ok();
    if let Ok(json) = serde_json::to_string_pretty(&class_meta) {
        fs::write(target_dir.join(format!("{}.json", struct_name_ident)), json).ok();
    }

    quote! { #input_impl #(#extra_functions)* }.into()
}
