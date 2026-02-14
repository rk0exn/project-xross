use crate::codegen::ffi::{
    add_clone_method, generate_common_ffi, generate_enum_aux_ffi, generate_enum_layout,
};
use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use xross_metadata::{ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossVariant};

pub fn impl_enum_derive(
    e: &syn::ItemEnum,
    crate_name: &str,
    extra_functions: &mut Vec<TokenStream>,
) -> TokenStream {
    let name = &e.ident;
    let name_str = name.to_string();
    let package = extract_package(&e.attrs);
    let symbol_base = build_symbol_base(crate_name, &package, &name_str);

    let layout_logic = generate_enum_layout(e);
    let is_clonable = extract_is_clonable(&e.attrs);

    let mut variants = Vec::new();
    let mut methods = Vec::new();
    let mut variant_name_arms = Vec::new();

    if is_clonable {
        add_clone_method(&mut methods, &symbol_base, &package, &name_str);
    }

    for v in &e.variants {
        let v_ident = &v.ident;
        let v_str = v_ident.to_string();
        let mut v_fields = Vec::new();
        let constructor_name = format_ident!("{}_new_{}", symbol_base, v_ident);

        let mut c_param_defs = Vec::new();
        let mut internal_conversions = Vec::new();
        let mut call_args = Vec::new();

        for (i, field) in v.fields.iter().enumerate() {
            let field_name =
                field.ident.as_ref().map(|id| id.to_string()).unwrap_or_else(|| ordinal_name(i));
            let ty = resolve_type_with_attr(&field.ty, &field.attrs, &package, Some(name));

            v_fields.push(XrossField {
                name: field_name,
                ty: ty.clone(),
                safety: ThreadSafety::Lock,
                docs: extract_docs(&field.attrs),
            });

            let arg_id = format_ident!("arg_{}", i);
            let (c_arg, conv, call_arg) =
                crate::codegen::ffi::gen_arg_conversion(&field.ty, &arg_id, &ty);
            c_param_defs.push(c_arg);
            internal_conversions.push(conv);

            if let Some(id) = &field.ident {
                call_args.push(quote! { #id: #call_arg });
            } else {
                call_args.push(quote! { #call_arg });
            }
        }

        let enum_construct = if v.fields.is_empty() {
            variant_name_arms.push(quote!(#name::#v_ident => #v_str));
            quote! { #name::#v_ident }
        } else if matches!(v.fields, syn::Fields::Named(_)) {
            variant_name_arms.push(quote!(#name::#v_ident { .. } => #v_str));
            quote! { #name::#v_ident { #(#call_args),* } }
        } else {
            variant_name_arms.push(quote!(#name::#v_ident(..) => #v_str));
            quote! { #name::#v_ident(#(#call_args),*) }
        };

        extra_functions.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #name {
                #(#internal_conversions)*
                Box::into_raw(Box::new(#enum_construct))
            }
        });

        variants.push(XrossVariant { name: v_str, fields: v_fields, docs: extract_docs(&v.attrs) });
    }

    save_definition(&XrossDefinition::Enum(XrossEnum {
        signature: if package.is_empty() {
            name_str.clone()
        } else {
            format!("{}.{}", package, name_str)
        },
        symbol_prefix: symbol_base.clone(),
        package_name: package,
        name: name_str,
        variants,
        methods,
        docs: extract_docs(&e.attrs),
        is_copy: extract_is_copy(&e.attrs),
    }));

    let mut toks = Vec::new();
    generate_common_ffi(name, &symbol_base, layout_logic, &mut toks, is_clonable);

    generate_enum_aux_ffi(name, &symbol_base, variant_name_arms, &mut toks);
    quote!(#(#toks)*)
}
