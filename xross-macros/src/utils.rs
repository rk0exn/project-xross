use heck::ToSnakeCase;
use syn::{Attribute, Expr, ExprLit, Lit, Meta, Token};
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
    if let syn::Type::Path(tp) = ty
        && let Some(last_segment) = tp.path.segments.last()
        && let syn::PathArguments::AngleBracketed(args) = &last_segment.arguments
        && let Some(syn::GenericArgument::Type(inner)) = args.args.first()
    {
        return inner;
    }
    ty
}

pub fn extract_inner_type_from_res(ty: &syn::Type, is_ok: bool) -> &syn::Type {
    if let syn::Type::Path(tp) = ty
        && let Some(last_segment) = tp.path.segments.last()
        && let syn::PathArguments::AngleBracketed(args) = &last_segment.arguments
    {
        let idx = if is_ok { 0 } else { 1 };
        if let Some(syn::GenericArgument::Type(inner)) = args.args.get(idx) {
            return inner;
        }
    }
    ty
}
/// Returns the English ordinal name as a word (no digits).
/// For example: 0 → "zeroth", 1 → "first", 21 → "twenty-first", 100 → "one hundredth"
pub fn ordinal_name(i: usize) -> String {
    if i == 0 {
        return "zeroth".to_string();
    }

    let small = [
        "",
        "first",
        "second",
        "third",
        "fourth",
        "fifth",
        "sixth",
        "seventh",
        "eighth",
        "ninth",
        "tenth",
        "eleventh",
        "twelfth",
        "thirteenth",
        "fourteenth",
        "fifteenth",
        "sixteenth",
        "seventeenth",
        "eighteenth",
        "nineteenth",
        "twentieth",
    ];

    if i <= 20 {
        return small[i].to_string();
    }

    // ここから再帰的に大きな数を扱う
    if i >= 1_000_000 {
        let millions = i / 1_000_000;
        let remainder = i % 1_000_000;

        let million_part = if millions == 1 {
            "one_million".to_string()
        } else {
            format!("{}_million", cardinal_to_ordinal(millions))
        };

        if remainder == 0 {
            return format!("{}th", million_part);
        } else {
            return format!("{}_{}", million_part, ordinal_name(remainder));
        }
    }

    if i >= 1_000 {
        let thousands = i / 1_000;
        let remainder = i % 1_000;

        let thousand_part = if thousands == 1 {
            "one_thousand".to_string()
        } else {
            format!("{}_thousand", cardinal_to_ordinal(thousands))
        };

        if remainder == 0 {
            return format!("{}th", thousand_part);
        } else {
            return format!("{}_{}", thousand_part, ordinal_name(remainder));
        }
    }

    if i >= 100 {
        let hundreds = i / 100;
        let remainder = i % 100;

        let hundred_part = if hundreds == 1 {
            "one_hundred".to_string()
        } else {
            format!("{}_hundred", cardinal_to_ordinal(hundreds))
        };

        if remainder == 0 {
            return format!("{}th", hundred_part);
        } else {
            return format!("{}_{}", hundred_part, ordinal_name(remainder));
        }
    }

    // 21〜99
    let tens = i / 10;
    let ones = i % 10;

    let ten_names =
        ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

    let mut result = ten_names[tens].to_string();

    if ones > 0 {
        result.push('-');
        result.push_str(small[ones]);
    }

    result
}

// 補助関数：基数 → 序数（1→first, 2→second, ...）の再帰的変換
// （ordinal_nameの内部で使うために簡易版）
fn cardinal_to_ordinal(n: usize) -> String {
    if n <= 20 {
        let small = [
            "",
            "first",
            "second",
            "third",
            "fourth",
            "fifth",
            "sixth",
            "seventh",
            "eighth",
            "ninth",
            "tenth",
            "eleventh",
            "twelfth",
            "thirteenth",
            "fourteenth",
            "fifteenth",
            "sixteenth",
            "seventeenth",
            "eighteenth",
            "nineteenth",
            "twentieth",
        ];
        return small[n].to_string();
    }

    // ここでは簡易的に末尾だけthにする（完全ではないが実用的）
    // 必要ならさらに細かく拡張可能
    let s = ordinal_name(n);
    if s.ends_with("first") || s.ends_with("second") || s.ends_with("third") {
        s
    } else {
        format!("{}th", s.trim_end_matches(|c: char| c.is_alphabetic() && !c.is_whitespace()))
    }
}
