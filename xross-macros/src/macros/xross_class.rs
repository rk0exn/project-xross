use crate::codegen::ffi::{
    add_clone_method, build_signature, generate_common_ffi, generate_enum_aux_ffi,
    generate_property_accessors, process_method_args, resolve_return_type, write_ffi_function,
    MethodFfiData,
};
use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use quote::{format_ident, quote};
use syn::parse::{Parse, ParseStream};
use syn::{braced, parenthesized, parse_macro_input, FnArg, ReturnType, Signature, Token, Type};
use xross_metadata::{
    ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossStruct, XrossVariant,
};

syn::custom_keyword!(package);
syn::custom_keyword!(class);
syn::custom_keyword!(variants);
syn::custom_keyword!(clonable);
syn::custom_keyword!(is_clonable);
syn::custom_keyword!(iscopy);
syn::custom_keyword!(is_copy);
syn::custom_keyword!(field);
syn::custom_keyword!(method);

enum VariantFieldInfo {
    Unit,
    Named(Vec<(String, Type)>),
    Unnamed(Vec<Type>),
}

struct VariantInfo {
    name: String,
    fields: VariantFieldInfo,
}

enum XrossClassItem {
    Package(String),
    Class(String),
    Enum(String),
    IsClonable(bool),
    IsCopy(bool),
    Field { name: String, ty: Type },
    Method(Signature, Option<String>),
    Variants(Vec<VariantInfo>),
}

struct XrossClassInput {
    items: Vec<XrossClassItem>,
}

impl Parse for XrossClassInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let mut items = Vec::new();
        while !input.is_empty() {
            if input.peek(package) {
                input.parse::<package>()?;
                let pkg = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Package(pkg));
            } else if input.peek(class) {
                input.parse::<class>()?;
                input.parse::<Token![struct]>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Class(name));
            } else if input.peek(Token![enum]) {
                input.parse::<Token![enum]>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Enum(name));
            } else if input.peek(variants) {
                input.parse::<variants>()?;
                let content;
                braced!(content in input);
                let mut v_list = Vec::new();
                while !content.is_empty() {
                    let v_name = content.parse::<syn::Ident>()?.to_string();
                    let v_fields = if content.peek(syn::token::Brace) {
                        let field_content;
                        braced!(field_content in content);
                        let mut named = Vec::new();
                        while !field_content.is_empty() {
                            let f_name = field_content.parse::<syn::Ident>()?.to_string();
                            field_content.parse::<Token![:]>()?;
                            let f_ty = field_content.parse::<Type>()?;
                            if field_content.peek(Token![;]) { field_content.parse::<Token![;]>()?; }
                            named.push((f_name, f_ty));
                        }
                        VariantFieldInfo::Named(named)
                    } else if content.peek(syn::token::Paren) {
                        let field_content;
                        parenthesized!(field_content in content);
                        let mut unnamed = Vec::new();
                        while !field_content.is_empty() {
                            unnamed.push(field_content.parse::<Type>()?);
                            if field_content.peek(Token![,]) { field_content.parse::<Token![,]>()?; }
                        }
                        VariantFieldInfo::Unnamed(unnamed)
                    } else {
                        VariantFieldInfo::Unit
                    };
                    if content.peek(Token![;]) { content.parse::<Token![;]>()?; }
                    v_list.push(VariantInfo { name: v_name, fields: v_fields });
                }
                if input.peek(Token![;]) { input.parse::<Token![;]>()?; }
                items.push(XrossClassItem::Variants(v_list));
            } else if input.peek(clonable) || input.peek(is_clonable) {
                if input.peek(clonable) { input.parse::<clonable>()?; }
                else { input.parse::<is_clonable>()?; }
                let val: syn::LitBool = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::IsClonable(val.value));
            } else if input.peek(iscopy) || input.peek(is_copy) {
                if input.peek(iscopy) { input.parse::<iscopy>()?; }
                else { input.parse::<is_copy>()?; }
                let val: syn::LitBool = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::IsCopy(val.value));
            } else if input.peek(field) {
                input.parse::<field>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![:]>()?;
                let ty: Type = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Field { name, ty });
            } else if input.peek(method) {
                input.parse::<method>()?;
                let mut inputs = syn::punctuated::Punctuated::<FnArg, Token![,]>::new();
                let mut type_override = None;
                if input.peek(Token![&]) || input.peek(Token![mut]) || input.peek(Token![self]) {
                    let receiver = input.parse::<FnArg>()?;
                    inputs.push(receiver);
                    if input.peek(Token![.]) { input.parse::<Token![.]>()?; }
                } else if input.peek(syn::Ident) && input.peek2(Token![.]) {
                    let type_name = input.parse::<syn::Ident>()?.to_string();
                    type_override = Some(type_name);
                    input.parse::<Token![.]>()?;
                }
                let ident = input.parse::<syn::Ident>()?;
                let content;
                parenthesized!(content in input);
                let args = content.parse_terminated(FnArg::parse, Token![,])?;
                for arg in args { inputs.push(arg); }
                let output = input.parse::<ReturnType>()?;
                if input.peek(Token![;]) { input.parse::<Token![;]>()?; }
                items.push(XrossClassItem::Method(Signature {
                    constness: None, asyncness: None, unsafety: None, abi: None,
                    fn_token: <Token![fn]>::default(), ident, generics: syn::Generics::default(),
                    paren_token: syn::token::Paren::default(), inputs, variadic: None, output,
                }, type_override));
            } else {
                return Err(input.error("expected one of: package, class, enum, variants, clonable, iscopy, field, method"));
            }
        }
        Ok(XrossClassInput { items })
    }
}

pub fn impl_xross_class(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as XrossClassInput);
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
            XrossClassItem::Class(c) => { name = c; is_enum = false; }
            XrossClassItem::Enum(e) => { name = e; is_enum = true; }
            XrossClassItem::IsClonable(v) => is_clonable = v,
            XrossClassItem::IsCopy(v) => is_copy = v,
            XrossClassItem::Field { name, ty } => fields_raw.push((name, ty)),
            XrossClassItem::Method(sig, type_override) => methods_raw.push((sig, type_override)),
            XrossClassItem::Variants(v) => variants_raw = v,
        }
    }

    if name.is_empty() { panic!("xross_class! requires a class or enum name"); }
    let type_ident = format_ident!("{}", name);
    let crate_name = std::env::var("CARGO_PKG_NAME").unwrap_or_else(|_| "unknown_crate".to_string()).replace("-", "_");
    let symbol_base = build_symbol_base(&crate_name, &package, &name);
    let mut extra_functions = Vec::new();
    let mut methods_meta = Vec::new();

    if is_clonable { add_clone_method(&mut methods_meta, &symbol_base, &package, &name); }

    for (sig, type_override) in methods_raw {
        let rust_fn_name = &sig.ident;
        let mut ffi_data = MethodFfiData::new(&symbol_base, rust_fn_name);
        process_method_args(&sig.inputs, &package, &type_ident, &mut ffi_data);
        let ret_ty = resolve_return_type(&sig.output, &[], &package, &type_ident);
        let is_constructor = if let ReturnType::Type(_, ty) = &sig.output {
            match &**ty { Type::Path(tp) => tp.path.is_ident(&name) || tp.path.is_ident("Self"), _ => false }
        } else { false };

        methods_meta.push(XrossMethod {
            name: rust_fn_name.to_string(), symbol: ffi_data.symbol_name.clone(), method_type: ffi_data.method_type,
            safety: ThreadSafety::Lock, is_constructor, args: ffi_data.args_meta.clone(), ret: ret_ty.clone(), docs: vec![],
        });

        let type_prefix = if let Some(to) = type_override { let ident = format_ident!("{}", to); quote! { #ident :: } }
            else { quote! { #type_ident :: } };
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
                        v_fields_meta.push(XrossField { name: f_name.clone(), ty: ty.clone(), safety: ThreadSafety::Lock, docs: vec![] });
                        let arg_id = format_ident!("arg_{}", f_name);
                        let f_name_ident = format_ident!("{}", f_name);
                        let (c_arg, conv, c_call_arg) = crate::codegen::ffi::gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg); internal_conversions.push(conv); call_args.push(quote! { #f_name_ident: #c_call_arg });
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
                        v_fields_meta.push(XrossField { name: f_name_str.clone(), ty: ty.clone(), safety: ThreadSafety::Lock, docs: vec![] });
                        let arg_id = format_ident!("arg_{}", i);
                        let (c_arg, conv, c_call_arg) = crate::codegen::ffi::gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg); internal_conversions.push(conv); call_args.push(c_call_arg);
                        let idx = syn::Index::from(i);
                        field_specs.push(quote! { { let offset = std::mem::offset_of!(#type_ident, #v_ident . #idx) as u64; let size = std::mem::size_of::<#f_ty>() as u64; format!("{}:{}:{}", #f_name_str, offset, size) } });
                    }
                    extra_functions.push(quote! { #[unsafe(no_mangle)] pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #type_ident { #(#internal_conversions)* Box::into_raw(Box::new(#type_ident::#v_ident(#(#call_args),*))) } });
                    variant_specs.push(quote! { format!("{}{{{}}}", #v_name_str, vec![#(#field_specs),*].join(";")) });
                    variant_name_arms.push(quote! { #type_ident::#v_ident(..) => #v_name_str });
                }
            }
            variants_meta.push(XrossVariant { name: v.name.clone(), fields: v_fields_meta, docs: vec![] });
        }
        save_definition(&XrossDefinition::Enum(XrossEnum { signature, symbol_prefix: symbol_base.clone(), package_name: package, name: name.clone(), variants: variants_meta, methods: methods_meta, docs: vec![], is_copy }));
        layout_logic = quote! { let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)]; let variants: Vec<String> = vec![#(#variant_specs),*]; parts.push(variants.join(";")); parts.join(";") };
        generate_enum_aux_ffi(&type_ident, &symbol_base, variant_name_arms, &mut extra_functions);
    } else {
        let mut fields_meta = Vec::new();
        let mut field_specs = Vec::new();
        for (f_name, f_ty) in fields_raw {
            let xross_ty = resolve_type_with_attr(&f_ty, &[], &package, Some(&type_ident));
            fields_meta.push(XrossField { name: f_name.clone(), ty: xross_ty.clone(), safety: ThreadSafety::Lock, docs: vec![] });
            let field_ident = format_ident!("{}", f_name);
            field_specs.push(quote! { { let offset = std::mem::offset_of!(#type_ident, #field_ident) as u64; let size = std::mem::size_of::<#f_ty>() as u64; format!("{}:{}:{}", #f_name, offset, size) } });
            generate_property_accessors(&type_ident, &field_ident, &f_ty, &xross_ty, &symbol_base, &mut extra_functions);
        }
        save_definition(&XrossDefinition::Struct(XrossStruct { signature, symbol_prefix: symbol_base.clone(), package_name: package, name, fields: fields_meta, methods: methods_meta, docs: vec![], is_copy }));
        layout_logic = quote! { let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)]; #(parts.push(#field_specs);)* parts.join(";") };
    }
    generate_common_ffi(&type_ident, &symbol_base, layout_logic, &mut extra_functions, is_clonable);
    quote! { #(#extra_functions)* }.into()
}
