use crate::metadata::discover_signature;
use crate::types::mapping::map_type;
use syn::{Attribute, Type};
use xross_metadata::{Ownership, XrossType};

pub fn resolve_type_with_attr(
    ty: &Type,
    attrs: &[Attribute],
    current_pkg: &str,
    current_ident: Option<&syn::Ident>,
) -> XrossType {
    let base_ty = map_type(ty);

    let (inner_ty, mut ownership) = match ty {
        Type::Reference(r) => {
            let ow = if r.mutability.is_some() { Ownership::MutRef } else { Ownership::Ref };
            (&*r.elem, ow)
        }
        _ => (ty, Ownership::Owned),
    };

    if let XrossType::Object { ownership: base_ow, .. } = &base_ty
        && *base_ow == Ownership::Boxed
    {
        ownership = Ownership::Boxed;
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

    if let Type::Path(tp) = inner_ty
        && tp.path.is_ident("Self")
        && let Some(ident) = current_ident
    {
        let sig = if current_pkg.is_empty() {
            ident.to_string()
        } else {
            format!("{}.{}", current_pkg, ident)
        };
        return XrossType::Object { signature: sig, ownership: ownership.clone() };
    }

    let mut final_ty = map_type(inner_ty);

    if let XrossType::Object { ownership: o, signature } = &mut final_ty {
        if ownership != Ownership::Owned {
            *o = ownership;
        }

        let is_self = current_ident.is_some_and(|ident| {
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

    final_ty
}
