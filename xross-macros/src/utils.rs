use crate::type_mapping::map_type;
use heck::ToSnakeCase;
use quote::{format_ident, quote};
use std::fs;
use std::path::PathBuf;
use syn::{Attribute, Expr, ExprLit, Lit, Meta, Type};
use xross_metadata::{Ownership, ThreadSafety, XrossDefinition, XrossType};

/// メタデータを出力する共通ディレクトリを取得する
pub fn get_xross_dir() -> PathBuf {
    // 1. 手動設定の環境変数を最優先
    if let Ok(val) = std::env::var("XROSS_METADATA_DIR") {
        return PathBuf::from(val);
    }

    // 2. OUT_DIR から遡って target ディレクトリを特定する
    // OUT_DIR は通常 target/debug/build/crate-hash/out のような形
    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir);
        // target/ フォルダまで遡る (通常 4つ上)
        let mut target = path.as_path();
        while let Some(parent) = target.parent() {
            if target.file_name().and_then(|n| n.to_str()) == Some("target") {
                return target.join("xross");
            }
            target = parent;
        }
    }

    // 3. CARGO_TARGET_DIR 環境変数をチェック
    if let Ok(val) = std::env::var("CARGO_TARGET_DIR") {
        return PathBuf::from(val).join("xross");
    }

    // 4. デフォルト: マクロがある場所からワークスペースのルートを探す
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap());

    let mut current = manifest_dir.as_path();
    let mut root = current;
    while let Some(parent) = current.parent() {
        if parent.join("Cargo.toml").exists() {
            root = parent;
            current = parent;
        } else {
            break;
        }
    }

    root.join("target").join("xross")
}

pub fn get_path_by_signature(signature: &str) -> PathBuf {
    get_xross_dir().join(format!("{}.json", signature))
}

pub fn save_definition(_ident: &syn::Ident, def: &XrossDefinition) {
    let xross_dir = get_xross_dir();
    fs::create_dir_all(&xross_dir).ok();
    let signature = def.signature();
    let path = get_path_by_signature(signature);

    if path.exists() {
        if let Ok(existing_content) = fs::read_to_string(&path) {
            if let Ok(existing_def) = serde_json::from_str::<XrossDefinition>(&existing_content) {
                if !is_structurally_compatible(&existing_def, def) {
                    panic!(
                        "\n[Xross Error] Duplicate definition detected for signature: '{}'\n\
                        The same signature is being defined multiple times with different structures.\n\
                        Please ensure that each XrossClass has a unique package or name.\n",
                        signature
                    );
                }
            }
        }
    }

    if let Ok(json) = serde_json::to_string(def) {
        fs::write(&path, json).ok();
    }
}

fn is_structurally_compatible(a: &XrossDefinition, b: &XrossDefinition) -> bool {
    match (a, b) {
        (XrossDefinition::Struct(sa), XrossDefinition::Struct(sb)) => {
            sa.package_name == sb.package_name
                && sa.name == sb.name
                && sa.fields.len() == sb.fields.len()
        }
        (XrossDefinition::Enum(ea), XrossDefinition::Enum(eb)) => {
            ea.package_name == eb.package_name
                && ea.name == eb.name
                && ea.variants.len() == eb.variants.len()
        }
        (XrossDefinition::Opaque(oa), XrossDefinition::Opaque(ob)) => {
            oa.package_name == ob.package_name && oa.name == ob.name
        }
        _ => false,
    }
}

pub fn load_definition(ident: &syn::Ident) -> Option<XrossDefinition> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path()) {
                if let Ok(def) = serde_json::from_str::<XrossDefinition>(&content) {
                    if def.name() == ident.to_string() {
                        return Some(def);
                    }
                }
            }
        }
    }
    None
}

pub fn discover_signature(type_name: &str) -> Option<String> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    let mut candidates = Vec::new();

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path()) {
                if let Ok(def) = serde_json::from_str::<XrossDefinition>(&content) {
                    if def.name() == type_name {
                        candidates.push(def.signature().to_string());
                    }
                }
            }
        }
    }

    candidates.sort();
    candidates.dedup();

    if candidates.len() == 1 {
        Some(candidates.remove(0))
    } else if candidates.len() > 1 {
        panic!(
            "\n[Xross Error] Ambiguous type reference: '{}'\n\
            Multiple types with the same name were found in different packages:\n\
            {}\n\
            Please use an explicit signature to disambiguate, for example:\n\
            #[xross(struct = \"full.package.Name\")]\n",
            type_name,
            candidates
                .iter()
                .map(|s| format!("  - {}", s))
                .collect::<Vec<_>>()
                .join("\n")
        );
    } else {
        None
    }
}

pub fn extract_package(attrs: &[Attribute]) -> String {
    for attr in attrs {
        if attr.path().is_ident("xross_package") {
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
        if attr.path().is_ident("xross_field") || attr.path().is_ident("xross_method") {
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

pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: proc_macro2::TokenStream,
    toks: &mut Vec<proc_macro2::TokenStream>,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("Xross{}Class", name);

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
            if !ptr.is_null() {
                drop(unsafe { Box::from_raw(ptr) });
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> *mut #name {
            if ptr.is_null() { return std::ptr::null_mut(); }
            let val_on_stack: #name = std::ptr::read_unaligned(ptr);
            let cloned_val = val_on_stack.clone();
            std::mem::forget(val_on_stack);
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
    _strict: bool,
) -> XrossType {
    let base_ty = map_type(ty);

    let (inner_ty, mut ownership) = match ty {
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

    if let XrossType::Object {
        ownership: base_ow, ..
    } = &base_ty
    {
        if *base_ow == Ownership::Boxed {
            ownership = Ownership::Boxed;
        }
    }

    let mut xross_ty = None;
    for attr in attrs {
        if attr.path().is_ident("xross") {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("struct")
                    || meta.path.is_ident("enum")
                    || meta.path.is_ident("opaque")
                {
                    xross_ty = Some(XrossType::Object {
                        signature: meta.value()?.parse::<syn::LitStr>()?.value(),
                        ownership: ownership.clone(),
                    });
                } else if meta.path.is_ident("box") {
                    xross_ty = Some(XrossType::Object {
                        signature: meta.value()?.parse::<syn::LitStr>()?.value(),
                        ownership: Ownership::Boxed,
                    });
                }
                Ok(())
            });
        }
    }
    if let Some(ty) = xross_ty {
        return ty;
    }

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

    let mut final_ty = map_type(inner_ty);

    match &mut final_ty {
        XrossType::Object {
            ownership: o,
            signature,
        } => {
            if ownership != Ownership::Owned {
                *o = ownership;
            }

            let is_self = current_ident.map_or(false, |ident| {
                let self_name = ident.to_string();
                signature == &self_name || signature == &format!("{}.{}", current_pkg, self_name)
            });

            if is_self {
                *signature = if current_pkg.is_empty() {
                    current_ident.unwrap().to_string()
                } else {
                    format!("{}.{}", current_pkg, current_ident.unwrap())
                };
            } else {
                if let Some(discovered) = discover_signature(signature) {
                    *signature = discovered;
                }
            }
        }
        _ => {}
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
            variant_specs.push(quote! {
                stringify!(#v_name).to_string()
            });
        } else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;
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
