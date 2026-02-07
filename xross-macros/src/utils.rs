use crate::type_mapping::map_type;
use heck::ToSnakeCase;
use quote::{format_ident, quote};
use std::fs;
use std::path::{Path, PathBuf};
use syn::{Attribute, Expr, ExprLit, Lit, Meta, Type};
use xross_metadata::{Ownership, ThreadSafety, XrossDefinition, XrossType};

const XROSS_DIR: &str = "target/xross";

pub fn get_path(ident: &syn::Ident) -> PathBuf {
    Path::new(XROSS_DIR).join(format!("{}.json", ident))
}

pub fn save_definition(ident: &syn::Ident, def: &XrossDefinition) {
    fs::create_dir_all(XROSS_DIR).ok();
    if let Ok(json) = serde_json::to_string(def) {
        fs::write(get_path(ident), json).ok();
    }
}

pub fn load_definition(ident: &syn::Ident) -> Option<XrossDefinition> {
    let path = get_path(ident);
    if let Ok(content) = fs::read_to_string(&path) {
        return serde_json::from_str(&content).ok();
    }
    None
}

pub fn extract_package(attrs: &[Attribute]) -> String {
    for attr in attrs {
        if attr.path().is_ident("jvm_package") {
            if let Ok(lit) = attr.parse_args::<Lit>() {
                if let Lit::Str(s) = lit {
                    return s.value();
                }
            }
        }
    }
    "".to_string()
}

pub fn extract_docs(attrs: &[Attribute]) -> Vec<String> {
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

pub fn extract_safety_attr(attrs: &[Attribute], default: ThreadSafety) -> ThreadSafety {
    for attr in attrs {
        if attr.path().is_ident("jvm_field") || attr.path().is_ident("jvm_method") {
            let mut safety = default;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("safety") {
                    let value = meta.value()?.parse::<syn::Ident>()?;
                    safety = match value.to_string().as_str() {
                        "Unsafe" => ThreadSafety::Unsafe,
                        "Atomic" => ThreadSafety::Atomic,
                        "Immutable" => ThreadSafety::Immutable,
                        "Lock" => ThreadSafety::Lock,
                        _ => default,
                    };
                }
                Ok(())
            });
            return safety;
        }
    }
    default
}

pub fn build_symbol_base(crate_name: &str, package: &str, type_name: &str) -> String {
    let type_snake = type_name.to_snake_case();
    if package.is_empty() {
        format!("{}_{}", crate_name, type_snake)
    } else {
        format!(
            "{}_{}_{}",
            crate_name,
            package.replace(".", "_"),
            type_snake
        )
    }
}

// 共通関数の生成ロジック
pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: proc_macro2::TokenStream,
    toks: &mut Vec<proc_macro2::TokenStream>,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("XrossJvm{}Class", name);

    toks.push(quote! {
        pub trait #trait_name {
            fn xross_layout() -> String;
        }

        impl #trait_name for #name {
            fn xross_layout() -> String {
                #layout_logic
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_id(ptr: *mut #name) {
            // Box::from_raw は「そのアドレスが malloc された先頭であること」を要求します。
            // もしズレたアドレス（フィールドへのポインタ）が渡されるとここで死ぬため、
            // Kotlin側の isBorrowed フラグが正しく機能していることが前提となります。
            if !ptr.is_null() {
                drop(unsafe { Box::from_raw(ptr) });
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> *mut #name {
            if ptr.is_null() { return std::ptr::null_mut(); }

            // 1. 参照を作らず、ポインタから直接スタック上に値をビットコピーする。
            // read_unaligned はアドレスがズレていても CPU 命令を駆使して安全に読み取ります。
            let val_on_stack: #name = std::ptr::read_unaligned(ptr);

            // 2. スタック上の値から clone() を呼び出す。
            let cloned_val = val_on_stack.clone();

            // 3. 元の値 (val_on_stack) が Drop されないように「忘れる」必要がある。
            // これをしないと、ptr の指し先にあるデータの所有権を奪って破棄したことになってしまいます。
            std::mem::forget(val_on_stack);

            // 4. クローンした値を Box に入れて生ポインタとして返す。
            Box::into_raw(Box::new(cloned_val))
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_id() -> *mut std::ffi::c_char {
            let s = <#name as #trait_name>::xross_layout();
            std::ffi::CString::new(s).unwrap().into_raw()
        }
    });
}

pub fn resolve_type_with_attr(
    ty: &Type,
    attrs: &[Attribute],
    current_pkg: &str,
    current_ident: Option<&syn::Ident>,
    strict: bool,
) -> XrossType {
    // 1. まず参照かどうかを判定し、中身の型(inner_ty)を特定する
    let (inner_ty, ownership) = match ty {
        Type::Reference(r) => {
            let ow = if r.mutability.is_some() {
                Ownership::MutRef
            } else {
                Ownership::Ref
            };
            (&*r.elem, ow)
        }
        _ => (ty, Ownership::Owned),
    };

    // 2. 属性による明示的な指定をチェック
    let mut xross_ty = None;
    for attr in attrs {
        if attr.path().is_ident("xross") {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("struct") {
                    xross_ty = Some(XrossType::Object {
                        signature: meta.value()?.parse::<syn::LitStr>()?.value(),
                        ownership: ownership.clone(), // 判定した所有権を適用
                    });
                } else if meta.path.is_ident("enum") {
                    xross_ty = Some(XrossType::Object {
                        signature: meta.value()?.parse::<syn::LitStr>()?.value(),
                        ownership: ownership.clone(),
                    });
                } else if meta.path.is_ident("opaque") {
                    xross_ty = Some(XrossType::Object {
                        signature: meta.value()?.parse::<syn::LitStr>()?.value(),
                        ownership: ownership.clone(),
                    });
                }
                Ok(())
            });
        }
    }
    if let Some(ty) = xross_ty {
        return ty;
    }

    // 3. Self 判定 (inner_ty を使用)
    if let Type::Path(tp) = inner_ty {
        if tp.path.is_ident("Self") {
            if let Some(ident) = current_ident {
                let sig = if current_pkg.is_empty() {
                    ident.to_string()
                } else {
                    format!("{}.{}", current_pkg, ident)
                };
                return XrossType::Object {
                    signature: sig,
                    ownership: ownership.clone(),
                };
            }
        }
    }

    // 4. 標準的な型マッピング (inner_ty を map_type に渡す)
    // ※ map_type が返す XrossType に ownership プロパティが含まれるよう修正されている前提
    let mut final_ty = map_type(inner_ty);

    // --- 5. Ownership の反映と Object(Unknown) のフォールバック ---
    match &mut final_ty {
        XrossType::Object {
            ownership: o,
            signature,
            ..
        } => {
            *o = ownership; // 冒頭で判定した参照情報を適用

            // 現在解析中の型名そのものを使っている場合 (例: MyService) の補完
            if let Some(ident) = current_ident {
                let self_name = ident.to_string();
                // 型名単体、またはフルパスで一致するか確認
                if signature == &self_name || signature == &format!("{}.{}", current_pkg, self_name)
                {
                    *signature = if current_pkg.is_empty() {
                        self_name
                    } else {
                        format!("{}.{}", current_pkg, self_name)
                    };
                }
            }
        }
        _ => {} // プリミティブ型などは Ownership を持たないので何もしない
    }

    // --- 6. 未解決型のバリデーション (strict = true の場合) ---
    if strict {
        if let XrossType::Object { signature, .. } = &final_ty {
            // プロジェクト内で定義された型(current_ident)でない、
            // かつ map_type でも解決できなかった型は、バインディング生成不能としてエラーにする
            let is_known_self = current_ident.map_or(false, |ident| {
                let self_name = ident.to_string();
                signature == &self_name || signature == &format!("{}.{}", current_pkg, self_name)
            });

            if !is_known_self {
                panic!(
                    "\n[Xross Error] Failed to resolve type: '{}'\n\
                    Context: Inside implementation of '{}'\n\n\
                    Possible solutions:\n\
                    1. If this is another JvmClass, use its full signature.\n\
                    2. Use 'Self' if you are referring to the current type.\n\
                    3. Use #[xross(opaque = \"package.Name\")] to explicitly define it as an opaque pointer.\n",
                    signature,
                    current_ident
                        .map(|i| i.to_string())
                        .unwrap_or_else(|| "Unknown".into())
                );
            }
        }
    }

    final_ty
}
pub fn generate_struct_layout(s: &syn::ItemStruct) -> proc_macro2::TokenStream {
    let name = &s.ident;
    let mut field_parts = Vec::new();

    if let syn::Fields::Named(fields) = &s.fields {
        for field in &fields.named {
            let f_name = field.ident.as_ref().unwrap();
            let f_ty = &field.ty;
            field_parts.push(quote! {
                {
                    let offset = std::mem::offset_of!(#name, #f_name) as u64;
                    let size = std::mem::size_of::<#f_ty>() as u64;
                    format!("{}:{}:{}", stringify!(#f_name), offset, size)
                }
            });
        }
    }

    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        #(parts.push(#field_parts);)*
        parts.join(";")
    }
}

pub fn generate_enum_layout(e: &syn::ItemEnum) -> proc_macro2::TokenStream {
    let name = &e.ident;
    let mut variant_specs = Vec::new();

    for v in &e.variants {
        let v_name = &v.ident;

        if v.fields.is_empty() {
            // フィールドがない場合は名前のみ。セパレーターは後で ";" になる。
            variant_specs.push(quote! {
                stringify!(#v_name).to_string()
            });
        } else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;

                // バリアント名とフィールド名（またはインデックス）を連結
                // 標準の offset_of! で Enum を扱うための構文: offset_of!(Enum, Variant.field)
                let f_access = if let Some(ident) = &field.ident {
                    quote! { #v_name . #ident }
                } else {
                    let index = syn::Index::from(i);
                    quote! { #v_name . #index }
                };

                let f_display_name = field
                    .ident
                    .as_ref()
                    .map(|id| id.to_string())
                    .unwrap_or_else(|| i.to_string());

                fields_info.push(quote! {
                    {
                        // Enumのバリアント内オフセット取得 (Nightly/Recent Stable)
                        let offset = std::mem::offset_of!(#name, #f_access) as u64;
                        let size = std::mem::size_of::<#f_ty>() as u64;
                        format!("{}:{}:{}", #f_display_name, offset, size)
                    }
                });
            }

            variant_specs.push(quote! {
                format!("{}{{{}}}", stringify!(#v_name), vec![#(#fields_info),*].join(";"))
            });
        }
    }

    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        let variants: Vec<String> = vec![#(#variant_specs),*];
        parts.push(variants.join(";"));
        parts.join(";")
    }
}
