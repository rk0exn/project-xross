use syn::{GenericArgument, PathArguments, Type, TypePath};
use xross_metadata::{Ownership, XrossType};

pub fn map_type(ty: &syn::Type) -> XrossType {
    match ty {
        Type::Reference(r) => map_type(&r.elem),

        Type::Slice(s) => XrossType::Slice(Box::new(map_type(&s.elem))),

        Type::Path(TypePath { path, .. }) => {
            let last_segment = path.segments.last().unwrap();
            let last_ident = last_segment.ident.to_string();

            match last_ident.as_str() {
                "i8" => XrossType::I8,
                "u8" => XrossType::U8,
                "i16" => XrossType::I16,
                "u16" => XrossType::U16,
                "i32" => XrossType::I32,
                "u32" => XrossType::U32,
                "i64" => XrossType::I64,
                "u64" => XrossType::U64,
                "isize" => XrossType::ISize,
                "usize" => XrossType::USize,
                "f32" => XrossType::F32,
                "f64" => XrossType::F64,
                "bool" => XrossType::Bool,
                "String" => XrossType::String,

                // ジェネリック型の処理
                "Box" | "Option" | "Result" | "Vec" | "VecDeque" | "LinkedList" | "HashSet"
                | "BTreeSet" | "BinaryHeap" | "HashMap" | "BTreeMap" => {
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
                            "Vec" => XrossType::Vec(Box::new(generic_types[0].clone())),
                            "VecDeque" => XrossType::VecDeque(Box::new(generic_types[0].clone())),
                            "LinkedList" => {
                                XrossType::LinkedList(Box::new(generic_types[0].clone()))
                            }
                            "HashSet" => XrossType::HashSet(Box::new(generic_types[0].clone())),
                            "BTreeSet" => XrossType::BTreeSet(Box::new(generic_types[0].clone())),
                            "BinaryHeap" => {
                                XrossType::BinaryHeap(Box::new(generic_types[0].clone()))
                            }
                            "HashMap" => XrossType::HashMap {
                                key: Box::new(generic_types[0].clone()),
                                value: Box::new(generic_types[1].clone()),
                            },
                            "BTreeMap" => XrossType::BTreeMap {
                                key: Box::new(generic_types[0].clone()),
                                value: Box::new(generic_types[1].clone()),
                            },
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
                s if s.chars().next().is_some_and(|c| c.is_uppercase()) => {
                    XrossType::Object { signature: s.to_string(), ownership: Ownership::Owned }
                }
                _ => XrossType::Pointer,
            }
        }
        _ => XrossType::Pointer,
    }
}

#[cfg(test)]
mod tests {
    use super::map_type;
    use syn::Type;
    use xross_metadata::XrossType;

    #[test]
    fn maps_std_collections() {
        let hash_map: Type = syn::parse_str("std::collections::HashMap<String, i32>").unwrap();
        let set: Type = syn::parse_str("std::collections::HashSet<u64>").unwrap();

        assert!(matches!(map_type(&hash_map), XrossType::HashMap { .. }));
        assert!(matches!(map_type(&set), XrossType::HashSet(_)));
    }

    #[test]
    fn maps_nested_option_result() {
        let nested: Type =
            syn::parse_str("Option<Result<Option<i32>, Result<String, u8>>>").unwrap();

        assert!(matches!(map_type(&nested), XrossType::Option(_)));
    }
}
