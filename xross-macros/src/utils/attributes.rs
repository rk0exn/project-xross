use syn::{Attribute, Expr, ExprLit, Lit, Meta, Token};
use xross_metadata::{HandleMode, ThreadSafety};

pub fn parse_critical_nested(meta: &syn::meta::ParseNestedMeta) -> syn::Result<bool> {
    let mut allow_heap_access = false;
    if meta.input.peek(syn::token::Paren) {
        let _ = meta.parse_nested_meta(|inner| {
            if inner.path.is_ident("heap_access") {
                allow_heap_access = true;
            }
            Ok(())
        });
    }
    Ok(allow_heap_access)
}

pub fn parse_handle_mode_nested(meta: &syn::meta::ParseNestedMeta) -> syn::Result<HandleMode> {
    if meta.input.peek(Token![=]) {
        let val: syn::LitStr = meta.value()?.parse()?;
        return match val.value().as_str() {
            "critical" => Ok(HandleMode::Critical { allow_heap_access: false }),
            "panicable" => Ok(HandleMode::Panicable),
            _ => Ok(HandleMode::Normal),
        };
    }
    if meta.input.peek(syn::token::Paren) {
        let mut mode = HandleMode::Normal;
        meta.parse_nested_meta(|inner| {
            if inner.path.is_ident("critical") {
                let allow_heap_access = parse_critical_nested(&inner)?;
                mode = HandleMode::Critical { allow_heap_access };
            } else if inner.path.is_ident("panicable") {
                mode = HandleMode::Panicable;
            }
            Ok(())
        })?;
        return Ok(mode);
    }
    Ok(HandleMode::Normal)
}

pub fn extract_handle_mode(attrs: &[Attribute]) -> HandleMode {
    let mut mode = HandleMode::Normal;

    for attr in attrs {
        let path = attr.path();
        if path.is_ident("xross_method")
            || path.is_ident("xross_new")
            || path.is_ident("xross_default")
            || path.is_ident("xross")
        {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("critical") {
                    let allow_heap_access = parse_critical_nested(&meta)?;
                    mode = HandleMode::Critical { allow_heap_access };
                } else if meta.path.is_ident("panicable") {
                    mode = HandleMode::Panicable;
                }
                Ok(())
            });
        }
    }

    mode
}

pub fn extract_special_modes(attrs: &[Attribute]) -> (HandleMode, HandleMode) {
    let mut clone_mode = HandleMode::Normal;
    let mut drop_mode = HandleMode::Normal;

    for attr in attrs {
        if attr.path().is_ident("xross") {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("clone") {
                    clone_mode = parse_handle_mode_nested(&meta)?;
                } else if meta.path.is_ident("drop") {
                    drop_mode = parse_handle_mode_nested(&meta)?;
                }
                Ok(())
            });
        }
    }
    (clone_mode, drop_mode)
}

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

pub fn extract_is_clonable(attrs: &[Attribute]) -> bool {
    // 1. Check #[derive(Clone)]
    let is_derived = attrs.iter().any(|attr| {
        if attr.path().is_ident("derive") {
            if let Meta::List(list) = &attr.meta {
                let folder = list.tokens.to_string();
                folder.contains("Clone")
            } else {
                false
            }
        } else {
            false
        }
    });

    if is_derived {
        return true;
    }

    // 2. Check #[xross(clonable)] or #[xross(clonable = true)]
    for attr in attrs {
        if attr.path().is_ident("xross") {
            let mut is_clonable = false;
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("clonable") {
                    if meta.input.peek(Token![=]) {
                        let value: syn::LitBool = meta.value()?.parse()?;
                        is_clonable = value.value;
                    } else {
                        is_clonable = true;
                    }
                }
                Ok(())
            });
            if is_clonable {
                return true;
            }
        }
    }

    false
}

pub fn extract_package(attrs: &[Attribute]) -> String {
    for attr in attrs {
        if attr.path().is_ident("xross_package")
            && let Ok(lit) = attr.parse_args::<Lit>()
            && let Lit::Str(s) = lit
        {
            return s.value();
        }
    }
    "".to_string()
}

pub fn extract_docs(attrs: &[Attribute]) -> Vec<String> {
    attrs
        .iter()
        .filter(|a| a.path().is_ident("doc"))
        .filter_map(|a| {
            if let Meta::NameValue(nv) = &a.meta
                && let Expr::Lit(ExprLit { lit: Lit::Str(s), .. }) = &nv.value
            {
                return Some(s.value().trim().to_string());
            }
            None
        })
        .collect()
}

pub fn extract_safety_attr(attrs: &[Attribute], default: ThreadSafety) -> ThreadSafety {
    let mut safety = default;
    let mut is_unsafe_acknowledged = false;

    for attr in attrs {
        if attr.path().is_ident("xross_field")
            || attr.path().is_ident("xross_method")
            || attr.path().is_ident("xross")
        {
            let _ = attr.parse_nested_meta(|meta| {
                if meta.path.is_ident("safety") {
                    let value = meta.value()?.parse::<syn::Ident>()?;
                    safety = match value.to_string().as_str() {
                        "Unsafe" => ThreadSafety::Unsafe,
                        "Direct" => ThreadSafety::Direct,
                        "Atomic" => ThreadSafety::Atomic,
                        "Immutable" => ThreadSafety::Immutable,
                        "Lock" => ThreadSafety::Lock,
                        _ => default,
                    };
                } else if meta.path.is_ident("unsafe") {
                    is_unsafe_acknowledged = true;
                }
                Ok(())
            });
        }
    }

    if (safety == ThreadSafety::Unsafe || safety == ThreadSafety::Direct) && !is_unsafe_acknowledged
    {
        panic!(
            "Using safety level 'Unsafe' or 'Direct' requires an explicit 'unsafe' attribute. e.g. #[xross_method(safety = Direct, unsafe)]"
        );
    }

    safety
}

pub fn extract_inner_type(ty: &syn::Type) -> &syn::Type {
    if let syn::Type::Path(tp) = ty
        && let Some(last_segment) = tp.path.segments.last()
        && let syn::PathArguments::AngleBracketed(args) = &last_segment.arguments
        && let Some(syn::GenericArgument::Type(inner)) = args.args.first()
    {
        return inner;
    }
    ty
}
