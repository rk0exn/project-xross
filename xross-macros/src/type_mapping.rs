use syn::{GenericArgument, PathArguments, Type, TypePath};
use xross_metadata::{Ownership, XrossType};

pub fn map_type(ty: &syn::Type) -> XrossType {
    match ty {
        Type::Reference(r) => map_type(&r.elem),

        Type::Path(TypePath { path, .. }) => {
            let last_segment = path.segments.last().unwrap();
            let last_ident = last_segment.ident.to_string();

            match last_ident.as_str() {
                "i8" => XrossType::I8,
                "i16" => XrossType::I16,
                "i32" => XrossType::I32,
                "i64" => XrossType::I64,
                "isize" => XrossType::ISize,
                "u16" => XrossType::U16,
                "usize" => XrossType::USize,
                "f32" => XrossType::F32,
                "f64" => XrossType::F64,
                "bool" => XrossType::Bool,
                "String" => XrossType::String,

                // ジェネリック型の処理
                "Box" | "Option" | "Result" => {
                    if let PathArguments::AngleBracketed(args) = &last_segment.arguments {
                        let generic_types: Vec<XrossType> = args
                            .args
                            .iter()
                            .filter_map(|arg| {
                                if let GenericArgument::Type(inner_ty) = arg {
                                    Some(map_type(inner_ty))
                                } else {
                                    None
                                }
                            })
                            .collect();

                        match last_ident.as_str() {
                            "Box" => {
                                let mut inner = generic_types[0].clone();
                                if let XrossType::Object { ownership, .. } = &mut inner {
                                    *ownership = Ownership::Boxed;
                                }
                                inner
                            }
                            "Option" => XrossType::Option(Box::new(generic_types[0].clone())),
                            "Result" => XrossType::Result {
                                ok: Box::new(generic_types[0].clone()),
                                err: Box::new(generic_types[1].clone()),
                            },
                            _ => unreachable!(),
                        }
                    } else {
                        XrossType::Pointer
                    }
                }

                // 構造体や列挙型
                s if s.chars().next().map_or(false, |c| c.is_uppercase()) => {
                    XrossType::Object { signature: s.to_string(), ownership: Ownership::Owned }
                }
                _ => XrossType::Pointer,
            }
        }
        _ => XrossType::Pointer,
    }
}
