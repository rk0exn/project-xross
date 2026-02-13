use crate::types::resolver::resolve_type_with_attr;
use crate::utils::{extract_inner_type, extract_safety_attr};
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::punctuated::Punctuated;
use syn::{Attribute, FnArg, Pat, Receiver, ReturnType, Type};
use xross_metadata::{
    Ownership, ThreadSafety, XrossField, XrossMethod, XrossMethodType, XrossType,
};

/// Data container for FFI method generation.
pub struct MethodFfiData {
    pub symbol_name: String,
    pub export_ident: syn::Ident,
    pub method_type: XrossMethodType,
    pub args_meta: Vec<XrossField>,
    pub c_args: Vec<TokenStream>,
    pub call_args: Vec<TokenStream>,
    pub conversion_logic: Vec<TokenStream>,
}

impl MethodFfiData {
    pub fn new(symbol_base: &str, rust_fn_name: &syn::Ident) -> Self {
        let symbol_name = format!("{}_{}", symbol_base, rust_fn_name);
        let export_ident = format_ident!("{}", symbol_name);
        Self {
            symbol_name,
            export_ident,
            method_type: XrossMethodType::Static,
            args_meta: Vec::new(),
            c_args: Vec::new(),
            call_args: Vec::new(),
            conversion_logic: Vec::new(),
        }
    }
}

/// Builds a full type signature.
pub fn build_signature(package: &str, name: &str) -> String {
    if package.is_empty() {
        name.to_string()
    } else {
        format!("{}.{}", package, name)
    }
}

/// Generates the actual FFI wrapper function.
pub fn write_ffi_function(
    ffi_data: &MethodFfiData,
    ret_ty: &XrossType,
    sig_output: &ReturnType,
    inner_call: TokenStream,
    toks: &mut Vec<TokenStream>,
) {
    let (c_ret_type, wrapper_body) = gen_ret_wrapping(ret_ty, sig_output, inner_call);
    let export_ident = &ffi_data.export_ident;
    let c_args = &ffi_data.c_args;
    let conv_logic = &ffi_data.conversion_logic;

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
            #(#conv_logic)*
            #wrapper_body
        }
    });
}

/// Generates common FFI functions (drop, clone, layout).
pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: proc_macro2::TokenStream,
    toks: &mut Vec<proc_macro2::TokenStream>,
    is_clonable: bool,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("Xross{}Class", name);

    toks.push(quote! {
        pub trait #trait_name { fn xross_layout() -> String; }
        impl #trait_name for #name { fn xross_layout() -> String { #layout_logic } }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_id(ptr: *mut #name) {
            if !ptr.is_null() { drop(unsafe { Box::from_raw(ptr) }); }
        }
    });

    if is_clonable {
        toks.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> *mut #name {
                if ptr.is_null() { return std::ptr::null_mut(); }
                let val_on_stack: #name = std::ptr::read_unaligned(ptr);
                let cloned_val = val_on_stack.clone();
                std::mem::forget(val_on_stack);
                Box::into_raw(Box::new(cloned_val))
            }
        });
    }

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_id() -> *mut std::ffi::c_char {
            let s = <#name as #trait_name>::xross_layout();
            std::ffi::CString::new(s).unwrap().into_raw()
        }
    });
}

/// Generates helper functions for Enums (tag and variant name).
pub fn generate_enum_aux_ffi(
    type_ident: &syn::Ident,
    symbol_base: &str,
    variant_name_arms: Vec<TokenStream>,
    toks: &mut Vec<TokenStream>,
) {
    let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
    let variant_name_fn_id = format_ident!("{}_get_variant_name", symbol_base);
    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #tag_fn_id(ptr: *const #type_ident) -> i32 {
            if ptr.is_null() { return -1; }
            *(ptr as *const i32)
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #type_ident) -> *mut std::ffi::c_char {
            if ptr.is_null() { return std::ptr::null_mut(); }
            let val = &*ptr;
            let name = match val { #(#variant_name_arms),* };
            std::ffi::CString::new(name).unwrap().into_raw()
        }
    });
}

/// Resolves the Xross return type and ownership.
pub fn resolve_return_type(
    output: &ReturnType,
    attrs: &[Attribute],
    package: &str,
    type_ident: &syn::Ident,
) -> XrossType {
    match output {
        ReturnType::Default => XrossType::Void,
        ReturnType::Type(_, ty) => {
            let mut xty = resolve_type_with_attr(ty, attrs, package, Some(type_ident));
            let ownership = match &**ty {
                Type::Reference(r) => {
                    if r.mutability.is_some() { Ownership::MutRef } else { Ownership::Ref }
                }
                _ => Ownership::Owned,
            };
            if let XrossType::Object { ownership: o, .. } = &mut xty { *o = ownership; }
            xty
        }
    }
}

/// Handles the receiver (&self, &mut self, self) conversion.
pub fn gen_receiver_logic(
    receiver: &Receiver,
    type_ident: &syn::Ident,
) -> (XrossMethodType, TokenStream, TokenStream) {
    let arg_ident = format_ident!("_self");
    let method_type = if receiver.reference.is_none() {
        XrossMethodType::OwnedInstance
    } else if receiver.mutability.is_some() {
        XrossMethodType::MutInstance
    } else {
        XrossMethodType::ConstInstance
    };

    let c_arg = quote! { #arg_ident: *mut std::ffi::c_void };
    let call_arg = if receiver.reference.is_none() {
        quote! { *Box::from_raw(#arg_ident as *mut #type_ident) }
    } else if receiver.mutability.is_some() {
        quote! { &mut *(#arg_ident as *mut #type_ident) }
    } else {
        quote! { &*(#arg_ident as *const #type_ident) }
    };

    (method_type, c_arg, call_arg)
}

/// Processes a list of function arguments.
pub fn process_method_args(
    inputs: &Punctuated<FnArg, syn::token::Comma>,
    package_name: &str,
    type_name_ident: &syn::Ident,
    ffi_data: &mut MethodFfiData,
) {
    for input in inputs {
        match input {
            FnArg::Receiver(receiver) => {
                let (m_ty, c_arg, call_arg) = gen_receiver_logic(receiver, type_name_ident);
                ffi_data.method_type = m_ty;
                ffi_data.c_args.push(c_arg);
                ffi_data.call_args.push(call_arg);
            }
            FnArg::Typed(pat_type) => {
                let arg_name = if let Pat::Ident(id) = &*pat_type.pat {
                    id.ident.to_string()
                } else {
                    "arg".into()
                };
                let arg_ident = format_ident!("{}", arg_name);
                let xross_ty = resolve_type_with_attr(
                    &pat_type.ty,
                    &pat_type.attrs,
                    package_name,
                    Some(type_name_ident),
                );

                ffi_data.args_meta.push(XrossField {
                    name: arg_name.clone(),
                    ty: xross_ty.clone(),
                    safety: extract_safety_attr(&pat_type.attrs, ThreadSafety::Lock),
                    docs: vec![],
                });

                let (c_arg, conv, call_arg) = gen_arg_conversion(&pat_type.ty, &arg_ident, &xross_ty);
                ffi_data.c_args.push(c_arg);
                ffi_data.conversion_logic.push(conv);
                ffi_data.call_args.push(call_arg);
            }
        }
    }
}

/// Adds a standard clone method to the methods list.
pub fn add_clone_method(
    methods: &mut Vec<XrossMethod>,
    symbol_base: &str,
    package: &str,
    name: &str,
) {
    methods.push(XrossMethod {
        name: "clone".to_string(),
        symbol: format!("{}_clone", symbol_base),
        method_type: XrossMethodType::ConstInstance,
        is_constructor: false,
        args: vec![],
        ret: XrossType::Object {
            signature: build_signature(package, name),
            ownership: Ownership::Owned,
        },
        safety: ThreadSafety::Lock,
        docs: vec!["Creates a clone of the native object.".to_string()],
    });
}

/// Helper to generate argument conversion logic.
pub fn gen_arg_conversion(
    arg_ty: &Type,
    arg_id: &syn::Ident,
    x_ty: &XrossType,
) -> (TokenStream, TokenStream, TokenStream) {
    match x_ty {
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
                    if p.path.is_ident("String") { quote!(#arg_id.to_string()) }
                    else { quote!(#arg_id) }
                } else { quote!(#arg_id) },
            )
        }
        XrossType::Object { ownership, .. } => (
            quote! { #arg_id: *mut std::ffi::c_void },
            match ownership {
                Ownership::Ref => quote! { let #arg_id = unsafe { &*(#arg_id as *const #arg_ty) }; },
                Ownership::MutRef => quote! { let #arg_id = unsafe { &mut *(#arg_id as *mut #arg_ty) }; },
                Ownership::Boxed => {
                    let inner = extract_inner_type(arg_ty);
                    quote! { let #arg_id = unsafe { Box::from_raw(#arg_id as *mut #inner) }; }
                }
                Ownership::Owned => quote! { let #arg_id = unsafe { *Box::from_raw(#arg_id as *mut #arg_ty) }; },
            },
            quote! { #arg_id },
        ),
        XrossType::Option(inner) => {
            let inner_rust_ty = extract_inner_type(arg_ty);
            (
                quote! { #arg_id: *mut std::ffi::c_void },
                if matches!(**inner, XrossType::String) {
                    quote! {
                        let #arg_id = unsafe {
                            if #arg_id.is_null() { None }
                            else { Some(std::ffi::CStr::from_ptr(#arg_id as *const _).to_str().unwrap_or("").to_string()) }
                        };
                    }
                } else {
                    quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { unsafe { Some(*Box::from_raw(#arg_id as *mut #inner_rust_ty)) } };
                    }
                },
                quote! { #arg_id },
            )
        }
        _ => (quote! { #arg_id: #arg_ty }, quote! {}, quote! { #arg_id }),
    }
}

/// Generates the layout metadata logic for a struct.
pub fn generate_struct_layout(s: &syn::ItemStruct) -> proc_macro2::TokenStream {
    let name = &s.ident;
    let mut field_parts = Vec::new();
    if let syn::Fields::Named(fields) = &s.fields {
        for field in &fields.named {
            let f_name = field.ident.as_ref().unwrap();
            let f_ty = &field.ty;
            field_parts.push(quote! {
                {
                    let offset = std::mem::offset_of!(#name, #f_name) as u64;
                    let size = std::mem::size_of::<#f_ty>() as u64;
                    format!("{}:{}:{}", stringify!(#f_name), offset, size)
                }
            });
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        #(parts.push(#field_parts);)*
        parts.join(";")
    }
}

/// Generates the layout metadata logic for an enum.
pub fn generate_enum_layout(e: &syn::ItemEnum) -> proc_macro2::TokenStream {
    let name = &e.ident;
    let mut variant_specs = Vec::new();
    for v in &e.variants {
        let v_name = &v.ident;
        if v.fields.is_empty() { variant_specs.push(quote! { stringify!(#v_name).to_string() }); }
        else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;
                let f_display_name = field.ident.as_ref().map(|id| id.to_string())
                    .unwrap_or_else(|| crate::utils::ordinal_name(i));
                let f_access = if let Some(ident) = &field.ident { quote! { #ident } }
                    else { let index = syn::Index::from(i); quote! { #index } };
                fields_info.push(quote! {
                    {
                        let offset = std::mem::offset_of!(#name, #v_name . #f_access) as u64;
                        let size = std::mem::size_of::<#f_ty>() as u64;
                        format!("{}:{}:{}", #f_display_name, offset, size)
                    }
                });
            }
            variant_specs.push(quote! { format!("{}{{{}}}", stringify!(#v_name), vec![#(#fields_info),*].join(";")) });
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        let variants: Vec<String> = vec![#(#variant_specs),*];
        parts.push(variants.join(";"));
        parts.join(";")
    }
}

/// Helper to generate return value wrapping logic.
pub fn gen_ret_wrapping(
    ret_ty: &XrossType,
    sig_output: &ReturnType,
    inner_call: TokenStream,
) -> (TokenStream, TokenStream) {
    match ret_ty {
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
            Ownership::Owned => (
                quote! { *mut std::ffi::c_void },
                quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
            ),
            Ownership::Boxed => (
                quote! { *mut std::ffi::c_void },
                quote! { Box::into_raw(#inner_call) as *mut std::ffi::c_void },
            ),
        },
        XrossType::Option(inner) => match &**inner {
            XrossType::String => (
                quote! { *mut std::ffi::c_char },
                quote! {
                    match #inner_call {
                        Some(s) => std::ffi::CString::new(s).unwrap_or_default().into_raw(),
                        None => std::ptr::null_mut(),
                    }
                },
            ),
            _ => (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match #inner_call {
                        Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                        None => std::ptr::null_mut(),
                    }
                },
            ),
        },
        XrossType::Result { ok, err } => {
            let gen_ptr = |ty: &XrossType, val_ident: TokenStream| match ty {
                XrossType::String => quote! { std::ffi::CString::new(#val_ident).unwrap_or_default().into_raw() as *mut std::ffi::c_void },
                _ => quote! { Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void },
            };
            let ok_ptr_logic = gen_ptr(ok, quote! { val });
            let err_ptr_logic = gen_ptr(err, quote! { e });
            (
                quote! { xross_core::XrossResult },
                quote! {
                    match #inner_call {
                        Ok(val) => xross_core::XrossResult { is_ok: true, ptr: #ok_ptr_logic },
                        Err(e) => xross_core::XrossResult { is_ok: false, ptr: #err_ptr_logic },
                    }
                },
            )
        }
        _ => {
            let raw_ret = if let ReturnType::Type(_, ty) = sig_output { quote! { #ty } } else { quote! { () } };
            (raw_ret, quote! { #inner_call })
        }
    }
}

/// Generates property accessors (getter/setter) for a field.
pub fn generate_property_accessors(
    type_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &XrossType,
    symbol_base: &str,
    toks: &mut Vec<TokenStream>,
) {
    let field_name = field_ident.to_string();
    match xross_ty {
        XrossType::String => {
            let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> *mut std::ffi::c_char {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let obj = &*ptr;
                    std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: *const std::ffi::c_char) {
                    if ptr.is_null() || val.is_null() { return; }
                    let obj = &mut *ptr;
                    obj.#field_ident = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                }
            });
        }
        _ => {
            let get_fn = format_ident!("{}_property_{}_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> #field_ty {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    (*ptr).#field_ident.clone()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: #field_ty) {
                    if ptr.is_null() { panic!("NULL pointer in property set"); }
                    (*ptr).#field_ident = val;
                }
            });
        }
    }
}
