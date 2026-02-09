mod type_mapping;
mod utils;

use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, ImplItem, ItemImpl, Pat, ReturnType, Type, parse_macro_input};
use utils::*;
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossMethodType,
    XrossStruct, XrossType, XrossVariant,
};

#[proc_macro_derive(JvmClass, attributes(jvm_field, jvm_package, xross))]
pub fn jvm_class_derive(input: TokenStream) -> TokenStream {
    let input = parse_macro_input!(input as syn::Item);

    // 実行時(コンパイル時)にマクロ呼び出し側のクレート名を取得
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let mut extra_functions = Vec::new();

    match input {
        syn::Item::Struct(s) => {
            let name = &s.ident;
            let package = extract_package(&s.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            // 1. レイアウト文字列生成ロジック (utils.rs の関数を使用)
            let layout_logic = generate_struct_layout(&s);

            // 2. メタデータ収集と保存
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
                    if field.attrs.iter().any(|a| a.path().is_ident("jvm_field")) {
                        let xross_ty = resolve_type_with_attr(
                            &field.ty,
                            &field.attrs,
                            &package,
                            Some(name),
                            false,
                        );
                        fields.push(XrossField {
                            name: field.ident.as_ref().unwrap().to_string(),
                            ty: xross_ty,
                            safety: extract_safety_attr(&field.attrs, ThreadSafety::Lock),
                            docs: extract_docs(&field.attrs),
                        });
                    }
                }
            }
            save_definition(
                name,
                &XrossDefinition::Struct(XrossStruct {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    symbol_prefix: symbol_base.clone(),
                    package_name: package,
                    name: name.to_string(),
                    fields,
                    methods, // 修正
                    docs: extract_docs(&s.attrs),
                }),
            );

            // 3. 共通FFI (Drop, Clone, Layout) の生成
            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);
        }

        syn::Item::Enum(e) => {
            let name = &e.ident;
            let package = extract_package(&e.attrs);
            let symbol_base = build_symbol_base(&crate_name, &package, &name.to_string());

            // 1. レイアウト文字列生成ロジック (utils.rs の関数を使用)
            let layout_logic = generate_enum_layout(&e);

            // 2. メタデータ収集と保存
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
                    let ty = resolve_type_with_attr(
                        &field.ty,
                        &field.attrs,
                        &package,
                        Some(name),
                        false,
                    );

                    v_fields.push(XrossField {
                        name: field_name,
                        ty: ty.clone(), // ここに Ownership 情報が含まれている
                        safety: ThreadSafety::Lock,
                        docs: extract_docs(&field.attrs),
                    });

                    let arg_id = format_ident!("arg_{}", i);
                    let raw_ty = &field.ty;

                    // ty.is_owned() を使って Box::from_raw するか判定
                    if ty.is_owned() {
                        c_param_defs.push(quote! { #arg_id: *mut std::ffi::c_void });
                        internal_conversions.push(
                            quote! { let #arg_id = *Box::from_raw(#arg_id as *mut #raw_ty); },
                        );
                    } else if matches!(
                        ty,
                            | XrossType::Object { .. }
                    ) {
                        // 参照(&T)の場合
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

                // バリアントごとのコンストラクタ FFI
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

            save_definition(
                name,
                &XrossDefinition::Enum(XrossEnum {
                    signature: if package.is_empty() {
                        name.to_string()
                    } else {
                        format!("{}.{}", package, name)
                    },
                    symbol_prefix: symbol_base.clone(),
                    package_name: package,
                    name: name.to_string(),
                    variants,
                    methods, // 修正
                    docs: extract_docs(&e.attrs),
                }),
            );

            // 3. 共通FFI
            generate_common_ffi(name, &symbol_base, layout_logic, &mut extra_functions);

            // 4. Enum タグ取得 FFI
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
                    // Rust Enum の判別子は先頭にあることを期待(repr(C, i32)等が推奨)
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
        _ => panic!("#[derive(JvmClass)] only supports Struct and Enum"),
    }

    quote!(#(#extra_functions)*).into()
}

#[proc_macro_attribute]
pub fn jvm_class(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut input_impl = parse_macro_input!(item as ItemImpl);
    let type_name_ident = if let Type::Path(tp) = &*input_impl.self_ty {
        &tp.path.segments.last().unwrap().ident
    } else {
        panic!("jvm_class must be used on a direct type implementation");
    };

    let mut definition = load_definition(type_name_ident)
        .expect("JvmClass definition not found. Apply #[derive(JvmClass)] first.");

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
                if attr.path().is_ident("jvm_new") {
                    is_new = true;
                    false
                } else if attr.path().is_ident("jvm_method") {
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
                            true,
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
                                // 引数の型が String (所有権あり) かどうかを判定
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
                                // 値渡し or 参照渡しをシグネチャから判断して deref する
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
                // コンストラクタ(jvm_new)は常に所有権を返す
                XrossType::Object {
                    signature: sig,
                    ownership: Ownership::Owned,
                }
            } else {
                match &method.sig.output {
                    ReturnType::Default => XrossType::Void,
                    ReturnType::Type(_, ty) => {
                        // 1. まず通常の型解決を行う
                        let mut xross_ty = resolve_type_with_attr(
                            ty,
                            &method.attrs,
                            &package_name,
                            Some(type_name_ident),
                            true,
                        );

                        // 2. 戻り値が参照 (&T や &mut T) かどうかを判定
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

                        // 3. XrossTypeが構造体やオブジェクトの場合、判定したOwnershipを上書きする
                        match &mut xross_ty {
                            | XrossType::Object { ownership: o, .. } => {
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
                XrossType::Object { ownership, .. } => {
                    match ownership {
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
                    }
                }
                XrossType::Option(inner) => {
                    match &**inner {
                        XrossType::String => (
                            quote! { *mut std::ffi::c_char },
                            quote! {
                                match #inner_call {
                                    Some(s) => std::ffi::CString::new(s).unwrap_or_default().into_raw(),
                                    None => std::ptr::null_mut(),
                                }
                            }
                        ),
                        XrossType::Object { .. } => (
                            quote! { *mut std::ffi::c_void },
                            quote! {
                                match #inner_call {
                                    Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                                    None => std::ptr::null_mut(),
                                }
                            }
                        ),
                        _ => {
                            // プリミティブ型の場合も Box 化してポインタで返す (Kotlin 側で T? として扱うため)
                            (
                                quote! { *mut std::ffi::c_void },
                                quote! {
                                    match #inner_call {
                                        Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                                        None => std::ptr::null_mut(),
                                    }
                                }
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

    save_definition(type_name_ident, &definition);
    quote! { #input_impl #(#extra_functions)* }.into()
}

#[proc_macro]
pub fn opaque_class(input: TokenStream) -> TokenStream {
    let input_str = input.to_string();
    // スペースが含まれる場合（例: com . example）を考慮して空白を除去しつつ分割
    let parts: Vec<String> = input_str.split(',').map(|s| s.replace(" ", "")).collect();

    let (package, name_str, is_clonable) = match parts.len() {
        1 => ("".to_string(), parts[0].as_str(), true),
        2 => {
            let second = parts[1].to_lowercase();
            if second == "true" || second == "false" {
                ("".to_string(), parts[0].as_str(), second.parse().unwrap())
            } else {
                (parts[0].clone(), parts[1].as_str(), true)
            }
        }
        3 => (
            parts[0].clone(),
            parts[1].as_str(),
            parts[2].to_lowercase().parse().unwrap_or(true),
        ),
        _ => panic!(
            "opaque_class! expects 1 to 3 arguments: (ClassName), (Pkg, Class), or (Pkg, Class, IsClonable)"
        ),
    };
    let name_ident = format_ident!("{}", name_str);

    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let symbol_base = build_symbol_base(&crate_name, &package, name_str);

    let mut methods = vec![];
    if is_clonable {
        methods.push(XrossMethod {
            name: "clone".to_string(),
            symbol: format!("{}_clone", symbol_base),
            method_type: XrossMethodType::ConstInstance,
            is_constructor: false,
            args: vec![],
            ret: XrossType::Object {
                signature: if package.is_empty() {
                    name_str.to_string()
                } else {
                    format!("{}.{}", package, name_str)
                },
                ownership: Ownership::Owned,
            },
            safety: ThreadSafety::Lock,
            docs: vec!["Creates a clone of the native object.".to_string()],
        });
    }

    // メタデータの保存 (is_clonable を追加)
    let definition = XrossDefinition::Opaque(xross_metadata::XrossOpaque {
        signature: if package.is_empty() {
            name_str.to_string()
        } else {
            format!("{}.{}", package, name_str)
        },
        symbol_prefix: symbol_base.clone(),
        package_name: package,
        name: name_str.to_string(),
        methods,
        is_clonable, // メタデータに反映
        docs: vec![format!("Opaque wrapper for {}", name_str)],
    });
    save_definition(&name_ident, &definition);

    let drop_fn = format_ident!("{}_drop", symbol_base);
    let size_fn = format_ident!("{}_size", symbol_base);

    // Clone 関数の生成条件分岐
    let clone_ffi = if is_clonable {
        let clone_fn = format_ident!("{}_clone", symbol_base);
        quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #clone_fn(ptr: *const #name_ident) -> *mut #name_ident {
                if ptr.is_null() { return std::ptr::null_mut(); }

                // 1. まず、型 T が安全に置けるスタック領域を確保する (MaybeUninit)
                let mut temp = std::mem::MaybeUninit::<#name_ident>::uninit();

                // 2. ptr から temp のメモリ領域へ、型のサイズ分だけ「生」でコピーする。
                // read_unaligned よりも ptr::copy_nonoverlapping の方が、
                // Rust のオブジェクトとしての整合性を問わずにビットを移せるので安全です。
                std::ptr::copy_nonoverlapping(
                    ptr as *const u8,
                    temp.as_mut_ptr() as *mut u8,
                    std::mem::size_of::<#name_ident>()
                );

                // 3. ここで初めて Rust の型として認識させる。
                // ただし、もし ptr の計算が 1 バイトでも間違っていると、ここで死ぬ可能性は残ります。
                let val_on_stack = temp.assume_init();

                // 4. クローンを作成し、元の stack 上のコピーは forget する（二重解放防止）。
                let cloned_val = val_on_stack.clone();
                std::mem::forget(val_on_stack);

                // 5. 新しい Box を作り、Java側へ返す。
                Box::into_raw(Box::new(cloned_val))
            }
        }
    } else {
        quote! {}
    };

    let generated = quote! {
        #clone_ffi

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_fn(ptr: *mut #name_ident) {
            if !ptr.is_null() {
                let _ = Box::from_raw(ptr);
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #size_fn() -> usize {
            std::mem::size_of::<#name_ident>()
        }
    };

    generated.into()
}
