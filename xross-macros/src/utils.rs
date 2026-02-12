use heck::ToSnakeCase;
use syn::{Attribute, Expr, ExprLit, Lit, Meta};
use xross_metadata::ThreadSafety;

pub fn extract_is_copy(attrs: &[Attribute]) -> bool {
    attrs.iter().any(|attr| {
        if attr.path().is_ident("derive") {
            if let Meta::List(list) = &attr.meta {
                let folder = list.tokens.to_string();
                folder.contains("Copy")
            } else {
                false
            }
        } else {
            false
        }
    })
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
                if let Expr::Lit(ExprLit { lit: Lit::Str(s), .. }) = &nv.value {
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
        format!("{}_{}_{}", crate_name, package.replace(".", "_"), type_snake)
    }
}

pub fn extract_inner_type(ty: &syn::Type) -> &syn::Type {
    if let syn::Type::Path(tp) = ty {
        if let Some(last_segment) = tp.path.segments.last() {
            if let syn::PathArguments::AngleBracketed(args) = &last_segment.arguments {
                if let Some(syn::GenericArgument::Type(inner)) = args.args.first() {
                    return inner;
                }
            }
        }
    }
    ty
}
