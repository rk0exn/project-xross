use crate::codegen::ffi::{
    add_clone_method, generate_common_ffi, generate_property_accessors, generate_struct_layout,
};
use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use xross_metadata::{ThreadSafety, XrossDefinition, XrossField, XrossStruct};

pub fn impl_struct_derive(
    s: &syn::ItemStruct,
    crate_name: &str,
    extra_functions: &mut Vec<TokenStream>,
) -> TokenStream {
    let name = &s.ident;
    let name_str = name.to_string();
    let package = extract_package(&s.attrs);
    let symbol_base = build_symbol_base(crate_name, &package, &name_str);

    let layout_logic = generate_struct_layout(s);
    let is_clonable = extract_is_clonable(&s.attrs);

    let mut fields = Vec::new();
    let mut methods = Vec::new();

    if is_clonable {
        add_clone_method(&mut methods, &symbol_base, &package, &name_str);
    }

    if let syn::Fields::Named(f) = &s.fields {
        for field in &f.named {
            if field.attrs.iter().any(|a| a.path().is_ident("xross_field")) {
                let field_ident = field.ident.as_ref().unwrap();
                let field_name = field_ident.to_string();
                let xross_ty =
                    resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));
                fields.push(XrossField {
                    name: field_name.clone(),
                    ty: xross_ty.clone(),
                    safety: extract_safety_attr(&field.attrs, ThreadSafety::Lock),
                    docs: extract_docs(&field.attrs),
                });

                generate_property_accessors(
                    name,
                    field_ident,
                    &field.ty,
                    &xross_ty,
                    &symbol_base,
                    extra_functions,
                );
            }
        }
    }
    save_definition(&XrossDefinition::Struct(XrossStruct {
        signature: if package.is_empty() {
            name_str.clone()
        } else {
            format!("{}.{}", package, name_str)
        },
        symbol_prefix: symbol_base.clone(),
        package_name: package,
        name: name_str,
        fields,
        methods,
        docs: extract_docs(&s.attrs),
        is_copy: extract_is_copy(&s.attrs),
    }));

    let mut toks = Vec::new();
    generate_common_ffi(name, &symbol_base, layout_logic, &mut toks, is_clonable);
    quote::quote!(#(#toks)*)
}
