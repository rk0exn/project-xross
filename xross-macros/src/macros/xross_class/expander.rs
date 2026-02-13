use crate::codegen::ffi::{
    MethodFfiData, add_clone_method, build_signature, generate_common_ffi, generate_enum_aux_ffi,
    generate_property_accessors, process_method_args, resolve_return_type, write_ffi_function,
};
use crate::macros::xross_class::parser::{VariantFieldInfo, XrossClassInput, XrossClassItem};
use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use quote::{format_ident, quote};
use syn::{ReturnType, Type};
use xross_metadata::{
    ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossStruct, XrossVariant,
};

pub fn impl_xross_class(input: XrossClassInput) -> proc_macro::TokenStream {
    let mut package = String::new();
    let mut name = String::new();
    let mut is_enum = false;
    let mut is_clonable = true;
    let mut is_copy = false;
    let mut fields_raw = Vec::new();
    let mut methods_raw = Vec::new();
    let mut variants_raw = Vec::new();

    for item in input.items {
        match item {
            XrossClassItem::Package(p) => package = p,
            XrossClassItem::Class(c) => {
                name = c;
                is_enum = false;
            }
            XrossClassItem::Enum(e) => {
                name = e;
                is_enum = true;
            }
            XrossClassItem::IsClonable(v) => is_clonable = v,
            XrossClassItem::IsCopy(v) => is_copy = v,
            XrossClassItem::Field { name, ty } => fields_raw.push((name, ty)),
            XrossClassItem::Method(sig, type_override) => methods_raw.push((sig, type_override)),
            XrossClassItem::Variants(v) => variants_raw = v,
        }
    }

    if name.is_empty() {
        panic!("xross_class! requires a class or enum name");
    }
    let type_ident = format_ident!("{}", name);
    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");
    let symbol_base = build_symbol_base(&crate_name, &package, &name);
    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    if is_clonable {
        add_clone_method(&mut methods_meta, &symbol_base, &package, &name);
    }

    for (sig, type_override) in methods_raw {
        let rust_fn_name = &sig.ident;
        let mut ffi_data = MethodFfiData::new(&symbol_base, rust_fn_name);
        process_method_args(&sig.inputs, &package, &type_ident, &mut ffi_data);
        let ret_ty = resolve_return_type(&sig.output, &[], &package, &type_ident);
        let is_constructor = if let ReturnType::Type(_, ty) = &sig.output {
            match &**ty {
                Type::Path(tp) => tp.path.is_ident(&name) || tp.path.is_ident("Self"),
                _ => false,
            }
        } else {
            false
        };

        methods_meta.push(XrossMethod {
            name: rust_fn_name.to_string(),
            symbol: ffi_data.symbol_name.clone(),
            method_type: ffi_data.method_type,
            safety: ThreadSafety::Lock,
            is_constructor,
            args: ffi_data.args_meta.clone(),
            ret: ret_ty.clone(),
            docs: vec![],
        });

        let type_prefix = if let Some(to) = type_override {
            let ident = format_ident!("{}", to);
            quote! { #ident :: }
        } else {
            quote! { #type_ident :: }
        };
        let call_args = &ffi_data.call_args;
        let inner_call = quote! { #type_prefix #rust_fn_name(#(#call_args),*) };
        write_ffi_function(&ffi_data, &ret_ty, &sig.output, inner_call, &mut extra_functions);
    }

    let layout_logic;
    let mut variant_name_arms = Vec::new();
    let signature = build_signature(&package, &name);

    if is_enum {
        let mut variants_meta = Vec::new();
        let mut variant_specs = Vec::new();
        for v in &variants_raw {
            let v_ident = format_ident!("{}", v.name);
            let v_name_str = &v.name;
            let constructor_name = format_ident!("{}_new_{}", symbol_base, v.name);
            let mut v_fields_meta = Vec::new();
            let mut c_param_defs = Vec::new();
            let mut internal_conversions = Vec::new();
            let mut call_args = Vec::new();
            let mut field_specs = Vec::new();

            match &v.fields {
                VariantFieldInfo::Unit => {
                    extra_functions.push(quote! { #[unsafe(no_mangle)] pub unsafe extern "C" fn #constructor_name() -> *mut #type_ident { Box::into_raw(Box::new(#type_ident::#v_ident)) } });
                    variant_specs.push(quote! { #v_name_str .to_string() });
                    variant_name_arms.push(quote! { #type_ident::#v_ident => #v_name_str });
                }
                VariantFieldInfo::Named(fields) => {
                    for (f_name, f_ty) in fields {
                        let ty = resolve_type_with_attr(f_ty, &[], &package, Some(&type_ident));
                        v_fields_meta.push(XrossField {
                            name: f_name.clone(),
                            ty: ty.clone(),
                            safety: ThreadSafety::Lock,
                            docs: vec![],
                        });
                        let arg_id = format_ident!("arg_{}", f_name);
                        let f_name_ident = format_ident!("{}", f_name);
                        let (c_arg, conv, c_call_arg) =
                            crate::codegen::ffi::gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg);
                        internal_conversions.push(conv);
                        call_args.push(quote! { #f_name_ident: #c_call_arg });
                        field_specs.push(quote! { { let offset = std::mem::offset_of!(#type_ident, #v_ident . #f_name_ident) as u64; let size = std::mem::size_of::<#f_ty>() as u64; format!("{}:{}:{}", #f_name, offset, size) } });
                    }
                    extra_functions.push(quote! { #[unsafe(no_mangle)] pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #type_ident { #(#internal_conversions)* Box::into_raw(Box::new(#type_ident::#v_ident { #(#call_args),* })) } });
                    variant_specs.push(quote! { format!("{}{{{}}}", #v_name_str, vec![#(#field_specs),*].join(";")) });
                    variant_name_arms.push(quote! { #type_ident::#v_ident { .. } => #v_name_str });
                }
                VariantFieldInfo::Unnamed(fields) => {
                    for (i, f_ty) in fields.iter().enumerate() {
                        let ty = resolve_type_with_attr(f_ty, &[], &package, Some(&type_ident));
                        let f_name_str = ordinal_name(i);
                        v_fields_meta.push(XrossField {
                            name: f_name_str.clone(),
                            ty: ty.clone(),
                            safety: ThreadSafety::Lock,
                            docs: vec![],
                        });
                        let arg_id = format_ident!("arg_{}", i);
                        let (c_arg, conv, c_call_arg) =
                            crate::codegen::ffi::gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg);
                        internal_conversions.push(conv);
                        call_args.push(c_call_arg);
                        let idx = syn::Index::from(i);
                        field_specs.push(quote! { { let offset = std::mem::offset_of!(#type_ident, #v_ident . #idx) as u64; let size = std::mem::size_of::<#f_ty>() as u64; format!("{}:{}:{}", #f_name_str, offset, size) } });
                    }
                    extra_functions.push(quote! { #[unsafe(no_mangle)] pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #type_ident { #(#internal_conversions)* Box::into_raw(Box::new(#type_ident::#v_ident(#(#call_args),*))) } });
                    variant_specs.push(quote! { format!("{}{{{}}}", #v_name_str, vec![#(#field_specs),*].join(";")) });
                    variant_name_arms.push(quote! { #type_ident::#v_ident(..) => #v_name_str });
                }
            }
            variants_meta.push(XrossVariant {
                name: v.name.clone(),
                fields: v_fields_meta,
                docs: vec![],
            });
        }
        save_definition(&XrossDefinition::Enum(XrossEnum {
            signature,
            symbol_prefix: symbol_base.clone(),
            package_name: package,
            name: name.clone(),
            variants: variants_meta,
            methods: methods_meta,
            docs: vec![],
            is_copy,
        }));
        layout_logic = quote! { let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)]; let variants: Vec<String> = vec![#(#variant_specs),*]; parts.push(variants.join(";")); parts.join(";") };
        generate_enum_aux_ffi(&type_ident, &symbol_base, variant_name_arms, &mut extra_functions);
    } else {
        let mut fields_meta = Vec::new();
        let mut field_specs = Vec::new();
        for (f_name, f_ty) in fields_raw {
            let xross_ty = resolve_type_with_attr(&f_ty, &[], &package, Some(&type_ident));
            fields_meta.push(XrossField {
                name: f_name.clone(),
                ty: xross_ty.clone(),
                safety: ThreadSafety::Lock,
                docs: vec![],
            });
            let field_ident = format_ident!("{}", f_name);
            field_specs.push(quote! { { let offset = std::mem::offset_of!(#type_ident, #field_ident) as u64; let size = std::mem::size_of::<#f_ty>() as u64; format!("{}:{}:{}", #f_name, offset, size) } });
            generate_property_accessors(
                &type_ident,
                &field_ident,
                &f_ty,
                &xross_ty,
                &symbol_base,
                &mut extra_functions,
            );
        }
        save_definition(&XrossDefinition::Struct(XrossStruct {
            signature,
            symbol_prefix: symbol_base.clone(),
            package_name: package,
            name,
            fields: fields_meta,
            methods: methods_meta,
            docs: vec![],
            is_copy,
        }));
        layout_logic = quote! { let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)]; #(parts.push(#field_specs);)* parts.join(";") };
    }
    generate_common_ffi(&type_ident, &symbol_base, layout_logic, &mut extra_functions, is_clonable);
    quote! { #(#extra_functions)* }.into()
}
