use crate::types::resolver::resolve_type_with_attr;
use crate::utils::extract_inner_type;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{Attribute, Receiver, ReturnType, Type};
use xross_metadata::{Ownership, XrossType};

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
    }
}

/// Handles the receiver (&self, &mut self, self) conversion.
pub fn gen_receiver_logic(
    receiver: &Receiver,
    type_ident: &syn::Ident,
) -> (xross_metadata::XrossMethodType, TokenStream, TokenStream) {
    let arg_ident = format_ident!("_self");
    let method_type = if receiver.reference.is_none() {
        xross_metadata::XrossMethodType::OwnedInstance
    } else if receiver.mutability.is_some() {
        xross_metadata::XrossMethodType::MutInstance
    } else {
        xross_metadata::XrossMethodType::ConstInstance
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
        XrossType::Option(inner) => {
            let inner_rust_ty = extract_inner_type(arg_ty);
            (
                quote! { #arg_id: *mut std::ffi::c_void },
                match &**inner {
                    XrossType::String => quote! {
                        let #arg_id = unsafe {
                            if #arg_id.is_null() { None }
                            else { Some(std::ffi::CStr::from_ptr(#arg_id as *const _).to_string_lossy().into_owned()) }
                        };
                    },
                    XrossType::Object { .. } => quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { unsafe { Some(*Box::from_raw(#arg_id as *mut #inner_rust_ty)) } };
                    },
                    XrossType::F32 => quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { Some(f32::from_bits(#arg_id as u32)) };
                    },
                    XrossType::F64 => quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { Some(f64::from_bits(#arg_id as u64)) };
                    },
                    _ => quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { Some(#arg_id as usize as #inner_rust_ty) };
                    },
                },
                quote! { #arg_id },
            )
        }
        XrossType::Result { ok, err: _ } => {
            let ok_inner = extract_inner_type(arg_ty);
            let gen_read = |ty: &XrossType, ptr: TokenStream, rust_ty: TokenStream| match ty {
                XrossType::String => {
                    quote! { std::ffi::CStr::from_ptr(#ptr as *const _).to_string_lossy().into_owned() }
                }
                XrossType::Object { .. } => quote! { *Box::from_raw(#ptr as *mut #rust_ty) },
                XrossType::F32 => quote! { f32::from_bits(#ptr as u32) },
                XrossType::F64 => quote! { f64::from_bits(#ptr as u64) },
                _ => quote! { #ptr as usize as #rust_ty },
            };
            let ok_read = gen_read(ok, quote! { #arg_id.ptr }, quote! { #ok_inner });
            (
                quote! { #arg_id: xross_core::XrossResult },
                quote! {
                    let #arg_id = if #arg_id.is_ok {
                        Ok(unsafe { #ok_read })
                    } else {
                        Err(unsafe {
                            if #arg_id.ptr.is_null() { "Unknown Error".to_string() }
                            else { std::ffi::CStr::from_ptr(#arg_id.ptr as *const _).to_string_lossy().into_owned() }
                        })
                    };
                },
                quote! { #arg_id },
            )
        }
        _ => (quote! { #arg_id: #arg_ty }, quote! {}, quote! { #arg_id }),
    }
}

/// Helper to generate the pointer representation of a single value for XrossResult.
pub fn gen_single_value_to_ptr(ty: &XrossType, val_ident: TokenStream) -> TokenStream {
    match ty {
        XrossType::String => {
            quote! { std::ffi::CString::new(#val_ident).unwrap_or_default().into_raw() as *mut std::ffi::c_void }
        }
        XrossType::Object { .. } => {
            quote! { Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void }
        }
        XrossType::F32 => quote! { #val_ident.to_bits() as usize as *mut std::ffi::c_void },
        XrossType::F64 => quote! { #val_ident.to_bits() as usize as *mut std::ffi::c_void },
        XrossType::Void => quote! { std::ptr::null_mut() },
        _ => quote! { #val_ident as usize as *mut std::ffi::c_void },
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
            XrossType::Object { .. } => (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match #inner_call {
                        Some(val) => Box::into_raw(Box::new(val)) as *mut std::ffi::c_void,
                        None => std::ptr::null_mut(),
                    }
                },
            ),
            XrossType::F32 | XrossType::F64 => (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match #inner_call {
                        Some(val) => val.to_bits() as usize as *mut std::ffi::c_void,
                        None => std::ptr::null_mut(),
                    }
                },
            ),
            _ => (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match #inner_call {
                        Some(val) => val as usize as *mut std::ffi::c_void,
                        None => std::ptr::null_mut(),
                    }
                },
            ),
        },
        XrossType::Result { ok, err } => {
            let ok_ptr_logic = gen_single_value_to_ptr(ok, quote! { val });
            let err_ptr_logic = gen_single_value_to_ptr(err, quote! { e });
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
            let raw_ret = if let ReturnType::Type(_, ty) = sig_output {
                quote! { #ty }
            } else {
                quote! { () }
            };
            (raw_ret, quote! { #inner_call })
        }
    }
}
