use crate::metadata::save_definition;
use crate::types::resolver::resolve_type_with_attr;
use crate::utils::*;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::parse::{Parse, ParseStream};
use syn::{
    FnArg, Pat, ReturnType, Signature, Token, Type, braced, parenthesized, parse_macro_input,
};
use xross_metadata::{
    Ownership, ThreadSafety, XrossDefinition, XrossEnum, XrossField, XrossMethod, XrossMethodType,
    XrossStruct, XrossType, XrossVariant,
};

syn::custom_keyword!(package);
syn::custom_keyword!(class);
syn::custom_keyword!(enum_kw);
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
                            if field_content.peek(Token![;]) {
                                field_content.parse::<Token![;]>()?;
                            }
                            named.push((f_name, f_ty));
                        }
                        VariantFieldInfo::Named(named)
                    } else if content.peek(syn::token::Paren) {
                        let field_content;
                        parenthesized!(field_content in content);
                        let mut unnamed = Vec::new();
                        while !field_content.is_empty() {
                            unnamed.push(field_content.parse::<Type>()?);
                            if field_content.peek(Token![,]) {
                                field_content.parse::<Token![,]>()?;
                            }
                        }
                        VariantFieldInfo::Unnamed(unnamed)
                    } else {
                        VariantFieldInfo::Unit
                    };

                    if content.peek(Token![;]) {
                        content.parse::<Token![;]>()?;
                    }
                    v_list.push(VariantInfo { name: v_name, fields: v_fields });
                }
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
                items.push(XrossClassItem::Variants(v_list));
            } else if input.peek(clonable) || input.peek(is_clonable) {
                if input.peek(clonable) {
                    input.parse::<clonable>()?;
                } else {
                    input.parse::<is_clonable>()?;
                }
                let val: syn::LitBool = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::IsClonable(val.value));
            } else if input.peek(iscopy) || input.peek(is_copy) {
                if input.peek(iscopy) {
                    input.parse::<iscopy>()?;
                } else {
                    input.parse::<is_copy>()?;
                }
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
                    if input.peek(Token![.]) {
                        input.parse::<Token![.]>()?;
                    }
                } else if input.peek(syn::Ident) && input.peek2(Token![.]) {
                    let type_name = input.parse::<syn::Ident>()?.to_string();
                    type_override = Some(type_name);
                    input.parse::<Token![.]>()?;
                }

                let ident = input.parse::<syn::Ident>()?;
                let content;
                parenthesized!(content in input);
                let args = content.parse_terminated(FnArg::parse, Token![,])?;
                for arg in args {
                    inputs.push(arg);
                }

                let output = input.parse::<ReturnType>()?;
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }

                items.push(XrossClassItem::Method(
                    Signature {
                        constness: None,
                        asyncness: None,
                        unsafety: None,
                        abi: None,
                        fn_token: <Token![fn]>::default(),
                        ident,
                        generics: syn::Generics::default(),
                        paren_token: syn::token::Paren::default(),
                        inputs,
                        variadic: None,
                        output,
                    },
                    type_override,
                ));
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

    // Helper to generate argument conversion logic
    let gen_arg_conversion = |arg_ty: &Type, arg_id: &syn::Ident, x_ty: &XrossType| match x_ty {
        XrossType::String => {
            let raw_id = format_ident!("{}_raw", arg_id);
            (
                quote! { #raw_id: *const std::ffi::c_char },
                quote! {
                    let #arg_id = unsafe {
                        if #raw_id.is_null() { "" }
                        else { std::ffi::CStr::from_ptr(#raw_id).to_str().unwrap_or("") }
                    };
                },
                if let Type::Path(p) = arg_ty {
                    if p.path.is_ident("String") {
                        quote!(#arg_id.to_string())
                    } else {
                        quote!(#arg_id)
                    }
                } else {
                    quote!(#arg_id)
                },
            )
        }
        XrossType::Object { ownership, .. } => (
            quote! { #arg_id: *mut std::ffi::c_void },
            match ownership {
                Ownership::Ref => {
                    quote! { let #arg_id = unsafe { &*(#arg_id as *const #arg_ty) }; }
                }
                Ownership::MutRef => {
                    quote! { let #arg_id = unsafe { &mut *(#arg_id as *mut #arg_ty) }; }
                }
                Ownership::Boxed => {
                    let inner = extract_inner_type(arg_ty);
                    quote! { let #arg_id = unsafe { Box::from_raw(#arg_id as *mut #inner) }; }
                }
                Ownership::Owned => {
                    quote! { let #arg_id = unsafe { *Box::from_raw(#arg_id as *mut #arg_ty) }; }
                }
            },
            quote! { #arg_id },
        ),
        _ => (quote! { #arg_id: #arg_ty }, quote! {}, quote! { #arg_id }),
    };

    // Process Common Methods
    let mut methods_meta = Vec::new();
    for (sig, type_override) in methods_raw {
        let rust_fn_name = &sig.ident;
        let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
        let export_ident = format_ident!("{}", symbol_name);

        let mut method_type = XrossMethodType::Static;
        let mut args_meta = Vec::new();
        let mut c_args = Vec::new();
        let mut call_args = Vec::new();
        let mut conversion_logic = Vec::new();

        for input_arg in &sig.inputs {
            match input_arg {
                FnArg::Receiver(receiver) => {
                    let arg_ident = format_ident!("_self");
                    method_type = if receiver.reference.is_none() {
                        XrossMethodType::OwnedInstance
                    } else if receiver.mutability.is_some() {
                        XrossMethodType::MutInstance
                    } else {
                        XrossMethodType::ConstInstance
                    };

                    c_args.push(quote! { #arg_ident: *mut std::ffi::c_void });
                    if receiver.reference.is_none() {
                        call_args.push(quote! { *Box::from_raw(#arg_ident as *mut #type_ident) });
                    } else if receiver.mutability.is_some() {
                        call_args.push(quote! { &mut *(#arg_ident as *mut #type_ident) });
                    } else {
                        call_args.push(quote! { &*(#arg_ident as *const #type_ident) });
                    }
                }
                FnArg::Typed(pat_type) => {
                    let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                        id.ident.to_string()
                    } else {
                        "arg".into()
                    };
                    let arg_ident = format_ident!("{}", arg_name);
                    let xross_ty =
                        resolve_type_with_attr(&pat_type.ty, &[], &package, Some(&type_ident));

                    args_meta.push(XrossField {
                        name: arg_name.clone(),
                        ty: xross_ty.clone(),
                        safety: ThreadSafety::Lock,
                        docs: vec![],
                    });

                    let (c_arg, conv, call_arg) =
                        gen_arg_conversion(&pat_type.ty, &arg_ident, &xross_ty);
                    c_args.push(c_arg);
                    conversion_logic.push(quote! { #conv });
                    call_args.push(call_arg);
                }
            }
        }

        let ret_ty = match &sig.output {
            ReturnType::Default => XrossType::Void,
            ReturnType::Type(_, ty) => {
                let mut xty = resolve_type_with_attr(ty, &[], &package, Some(&type_ident));
                let ownership = match &**ty {
                    Type::Reference(r) => {
                        if r.mutability.is_some() {
                            Ownership::MutRef
                        } else {
                            Ownership::Ref
                        }
                    }
                    _ => Ownership::Owned,
                };
                if let XrossType::Object { ownership: o, .. } = &mut xty {
                    *o = ownership;
                }
                xty
            }
        };

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
            symbol: symbol_name.clone(),
            method_type,
            safety: ThreadSafety::Lock,
            is_constructor,
            args: args_meta,
            ret: ret_ty.clone(),
            docs: vec![],
        });

        let type_prefix = if let Some(to) = type_override {
            let ident = format_ident!("{}", to);
            quote! { #ident :: }
        } else {
            quote! { #type_ident :: }
        };

        let inner_call = quote! { #type_prefix #rust_fn_name(#(#call_args),*) };
        let (c_ret_type, wrapper_body) = match &ret_ty {
            XrossType::Void => (quote! { () }, quote! { #inner_call; }),
            XrossType::String => (
                quote! { *mut std::ffi::c_char },
                quote! { std::ffi::CString::new(#inner_call).unwrap_or_default().into_raw() },
            ),
            XrossType::Object { ownership, .. } => match ownership {
                Ownership::Ref | Ownership::MutRef => (
                    quote! { *mut std::ffi::c_void },
                    quote! { #inner_call as *const _ as *mut std::ffi::c_void },
                ),
                _ => (
                    quote! { *mut std::ffi::c_void },
                    quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
                ),
            },
            XrossType::Result { ok, err } => {
                let gen_ptr = |ty: &XrossType, val_ident: proc_macro2::TokenStream| match ty {
                    XrossType::String => quote! {
                        std::ffi::CString::new(#val_ident).unwrap_or_default().into_raw() as *mut std::ffi::c_void
                    },
                    _ => quote! {
                        Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void
                    },
                };
                let ok_ptr_logic = gen_ptr(ok, quote! { val });
                let err_ptr_logic = gen_ptr(err, quote! { e });

                (
                    quote! { xross_core::XrossResult },
                    quote! {
                        match #inner_call {
                            Ok(val) => xross_core::XrossResult {
                                is_ok: true,
                                ptr: #ok_ptr_logic,
                            },
                            Err(e) => xross_core::XrossResult {
                                is_ok: false,
                                ptr: #err_ptr_logic,
                            },
                        }
                    },
                )
            }
            _ => {
                let raw_ret = if let ReturnType::Type(_, ty) = &sig.output {
                    quote! { #ty }
                } else {
                    quote! { () }
                };
                (raw_ret, quote! { #inner_call })
            }
        };

        extra_functions.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
                #(#conversion_logic)*
                #wrapper_body
            }
        });
    }

    let layout_logic;
    let mut variant_name_arms = Vec::new();

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
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #constructor_name() -> *mut #type_ident {
                            Box::into_raw(Box::new(#type_ident::#v_ident))
                        }
                    });
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
                        let f_name_str = f_name.clone();

                        let (c_arg, conv, c_call_arg) = gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg);
                        internal_conversions.push(conv);
                        call_args.push(quote! { #f_name_ident: #c_call_arg });

                        field_specs.push(quote! {
                            {
                                let offset = std::mem::offset_of!(#type_ident, #v_ident . #f_name_ident) as u64;
                                let size = std::mem::size_of::<#f_ty>() as u64;
                                format!("{}:{}:{}", #f_name_str, offset, size)
                            }
                        });
                    }
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #type_ident {
                            #(#internal_conversions)*
                            Box::into_raw(Box::new(#type_ident::#v_ident { #(#call_args),* }))
                        }
                    });
                    variant_specs.push(quote! {
                        format!("{}{{{}}}", #v_name_str, vec![#(#field_specs),*].join(";"))
                    });
                    variant_name_arms.push(quote! { #type_ident::#v_ident { .. } => #v_name_str });
                }
                VariantFieldInfo::Unnamed(fields) => {
                    for (i, f_ty) in fields.iter().enumerate() {
                        let ty = resolve_type_with_attr(f_ty, &[], &package, Some(&type_ident));
                        v_fields_meta.push(XrossField {
                            name: ordinal_name(i),
                            ty: ty.clone(),
                            safety: ThreadSafety::Lock,
                            docs: vec![],
                        });

                        let arg_id = format_ident!("arg_{}", i);
                        let f_name_str = ordinal_name(i);

                        let (c_arg, conv, c_call_arg) = gen_arg_conversion(f_ty, &arg_id, &ty);
                        c_param_defs.push(c_arg);
                        internal_conversions.push(conv);
                        call_args.push(c_call_arg);

                        let idx = syn::Index::from(i);
                        field_specs.push(quote! {
                            {
                                let offset = std::mem::offset_of!(#type_ident, #v_ident . #idx) as u64;
                                let size = std::mem::size_of::<#f_ty>() as u64;
                                format!("{}:{}:{}", #f_name_str, offset, size)
                            }
                        });
                    }
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #constructor_name(#(#c_param_defs),*) -> *mut #type_ident {
                            #(#internal_conversions)*
                            Box::into_raw(Box::new(#type_ident::#v_ident(#(#call_args),*)))
                        }
                    });
                    variant_specs.push(quote! {
                        format!("{}{{{}}}", #v_name_str, vec![#(#field_specs),*].join(";"))
                    });
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
            signature: if package.is_empty() {
                name.clone()
            } else {
                format!("{}.{}", package, name)
            },
            symbol_prefix: symbol_base.clone(),
            package_name: package,
            name: name.clone(),
            variants: variants_meta,
            methods: methods_meta,
            docs: vec![],
            is_copy,
        }));

        layout_logic = quote! {
            let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)];
            let variants: Vec<String> = vec![#(#variant_specs),*];
            parts.push(variants.join(";"));
            parts.join(";")
        };

        // Add tag and name functions for Enum
        let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
        let variant_name_fn_id = format_ident!("{}_get_variant_name", symbol_base);
        extra_functions.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #tag_fn_id(ptr: *const #type_ident) -> i32 {
                if ptr.is_null() { return -1; }
                *(ptr as *const i32)
            }

            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #type_ident) -> *mut std::ffi::c_char {
                if ptr.is_null() { return std::ptr::null_mut(); }
                let val = &*ptr;
                let name = match val {
                    #(#variant_name_arms),*
                };
                std::ffi::CString::new(name).unwrap().into_raw()
            }
        });
    } else {
        // Struct Processing
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
            let f_name_str = f_name.clone();

            field_specs.push(quote! {
                {
                    let offset = std::mem::offset_of!(#type_ident, #field_ident) as u64;
                    let size = std::mem::size_of::<#f_ty>() as u64;
                    format!("{}:{}:{}", #f_name_str, offset, size)
                }
            });

            match &xross_ty {
                XrossType::String => {
                    let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, f_name);
                    let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, f_name);
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #get_fn(ptr: *const #type_ident) -> *mut std::ffi::c_char {
                            if ptr.is_null() { return std::ptr::null_mut(); }
                            let obj = &*ptr;
                            std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                        }
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #set_fn(ptr: *mut #type_ident, val: *const std::ffi::c_char) {
                            if ptr.is_null() || val.is_null() { return; }
                            let obj = &mut *ptr;
                            let s = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                            obj.#field_ident = s;
                        }
                    });
                }
                _ => {
                    let get_fn = format_ident!("{}_property_{}_get", symbol_base, f_name);
                    let set_fn = format_ident!("{}_property_{}_set", symbol_base, f_name);
                    extra_functions.push(quote! {
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #get_fn(ptr: *const #type_ident) -> #f_ty {
                            if ptr.is_null() { panic!("NULL pointer in property get"); }
                            (*ptr).#field_ident.clone()
                        }
                        #[unsafe(no_mangle)]
                        pub unsafe extern "C" fn #set_fn(ptr: *mut #type_ident, val: #f_ty) {
                            if ptr.is_null() { panic!("NULL pointer in property set"); }
                            (*ptr).#field_ident = val;
                        }
                    });
                }
            }
        }

        save_definition(&XrossDefinition::Struct(XrossStruct {
            signature: if package.is_empty() {
                name.clone()
            } else {
                format!("{}.{}", package, name)
            },
            symbol_prefix: symbol_base.clone(),
            package_name: package,
            name,
            fields: fields_meta,
            methods: methods_meta,
            docs: vec![],
            is_copy,
        }));

        layout_logic = quote! {
            let mut parts = vec![format!("{}", std::mem::size_of::<#type_ident>() as u64)];
            #(parts.push(#field_specs);)*
            parts.join(";")
        };
    }

    // Generate Common FFI (drop, clone, layout)
    let drop_fn = format_ident!("{}_drop", symbol_base);
    let clone_fn = format_ident!("{}_clone", symbol_base);
    let layout_fn = format_ident!("{}_layout", symbol_base);

    let clone_ffi = if is_clonable {
        quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #clone_fn(ptr: *const #type_ident) -> *mut #type_ident {
                if ptr.is_null() { return std::ptr::null_mut(); }
                let val_on_stack: #type_ident = std::ptr::read_unaligned(ptr);
                let cloned_val = val_on_stack.clone();
                std::mem::forget(val_on_stack);
                Box::into_raw(Box::new(cloned_val))
            }
        }
    } else {
        quote! {}
    };

    let gen_code = quote! {
        #(#extra_functions)*

        #clone_ffi

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_fn(ptr: *mut #type_ident) {
            if !ptr.is_null() { drop(unsafe { Box::from_raw(ptr) }); }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_fn() -> *mut std::ffi::c_char {
            let s = { #layout_logic };
            std::ffi::CString::new(s).unwrap().into_raw()
        }
    };

    gen_code.into()
}
