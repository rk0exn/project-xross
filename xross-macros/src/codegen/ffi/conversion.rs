use crate::types::resolver::resolve_type_with_attr;
use crate::utils::{extract_base_type, extract_inner_type, is_primitive_type};
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{Attribute, Receiver, ReturnType, Type};
use xross_metadata::{Ownership, XrossType};

fn extract_result_types(ty: &Type) -> Option<(&Type, &Type)> {
    let ty = if let Type::Reference(r) = ty { &*r.elem } else { ty };
    let Type::Path(tp) = ty else { return None };
    let last = tp.path.segments.last()?;
    if last.ident != "Result" {
        return None;
    }
    let syn::PathArguments::AngleBracketed(args) = &last.arguments else {
        return None;
    };
    let mut iter = args.args.iter().filter_map(|arg| {
        if let syn::GenericArgument::Type(inner) = arg { Some(inner) } else { None }
    });
    Some((iter.next()?, iter.next()?))
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
        // Use ptr::read to avoid taking ownership of the pointer itself.
        // This prevents double free if Kotlin side also thinks it owns the memory.
        quote! { unsafe { std::ptr::read(#arg_ident as *const #type_ident) } }
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
            let ptr_id = format_ident!("{}_ptr", arg_id);
            let len_id = format_ident!("{}_len", arg_id);
            let enc_id = format_ident!("{}_enc", arg_id);
            (
                quote! { #ptr_id: *const u8, #len_id: usize, #enc_id: u8 },
                quote! {
                    let #arg_id = xross_core::XrossStringView {
                        ptr: #ptr_id,
                        len: #len_id,
                        encoding: #enc_id,
                    }.to_string_lossy();
                },
                quote!(#arg_id),
            )
        }
        XrossType::Slice(inner) => {
            let ptr_id = format_ident!("{}_ptr", arg_id);
            let len_id = format_ident!("{}_len", arg_id);
            let inner_rust_ty = match &**inner {
                XrossType::I8 => quote!(i8),
                XrossType::U8 => quote!(u8),
                XrossType::I16 => quote!(i16),
                XrossType::U16 => quote!(u16),
                XrossType::I32 => quote!(i32),
                XrossType::U32 => quote!(u32),
                XrossType::I64 => quote!(i64),
                XrossType::U64 => quote!(u64),
                XrossType::ISize => quote!(isize),
                XrossType::USize => quote!(usize),
                XrossType::F32 => quote!(f32),
                XrossType::F64 => quote!(f64),
                XrossType::Bool => quote!(bool),
                _ => quote!(std::ffi::c_void),
            };
            (
                quote! { #ptr_id: *const #inner_rust_ty, #len_id: usize },
                quote! {
                    let #arg_id = if #ptr_id.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(#ptr_id, #len_id) } };
                },
                quote!(#arg_id),
            )
        }
        XrossType::Vec(inner) => {
            let ptr_id = format_ident!("{}_ptr", arg_id);
            let len_id = format_ident!("{}_len", arg_id);
            let inner_rust_ty = match &**inner {
                XrossType::I8 => quote!(i8),
                XrossType::U8 => quote!(u8),
                XrossType::I16 => quote!(i16),
                XrossType::U16 => quote!(u16),
                XrossType::I32 => quote!(i32),
                XrossType::U32 => quote!(u32),
                XrossType::I64 => quote!(i64),
                XrossType::U64 => quote!(u64),
                XrossType::ISize => quote!(isize),
                XrossType::USize => quote!(usize),
                XrossType::F32 => quote!(f32),
                XrossType::F64 => quote!(f64),
                XrossType::Bool => quote!(bool),
                _ => quote!(std::ffi::c_void),
            };
            (
                quote! { #ptr_id: *const #inner_rust_ty, #len_id: usize },
                quote! {
                    let #arg_id = if #ptr_id.is_null() { Vec::new() } else { unsafe { std::slice::from_raw_parts(#ptr_id, #len_id).to_vec() } };
                },
                quote!(#arg_id),
            )
        }
        XrossType::Object { .. }
        | XrossType::VecDeque(_)
        | XrossType::LinkedList(_)
        | XrossType::HashSet(_)
        | XrossType::BTreeSet(_)
        | XrossType::BinaryHeap(_)
        | XrossType::HashMap { .. }
        | XrossType::BTreeMap { .. } => (
            quote! { #arg_id: *mut std::ffi::c_void },
            match x_ty {
                XrossType::Object { ownership, .. } => match ownership {
                    Ownership::Ref => {
                        let base = extract_base_type(arg_ty);
                        quote! { let #arg_id = unsafe { &*(#arg_id as *const #base) }; }
                    }
                    Ownership::MutRef => {
                        let base = extract_base_type(arg_ty);
                        quote! { let #arg_id = unsafe { &mut *(#arg_id as *mut #base) }; }
                    }
                    Ownership::Boxed => {
                        let inner = extract_inner_type(arg_ty);
                        quote! { let #arg_id = unsafe { Box::from_raw(#arg_id as *mut #inner) }; }
                    }
                    Ownership::Owned => {
                        let base = extract_base_type(arg_ty);
                        // Use ptr::read instead of Box::from_raw to avoid freeing memory that might be owned by Kotlin (e.g. Pure Enums).
                        quote! { let #arg_id = unsafe { std::ptr::read(#arg_id as *const #base) }; }
                    }
                },
                _ => {
                    quote! {
                        let #arg_id = if #arg_id.is_null() {
                            <#arg_ty as std::default::Default>::default()
                        } else {
                            unsafe { std::ptr::read(#arg_id as *const #arg_ty) }
                        };
                    }
                }
            },
            quote! { #arg_id },
        ),
        XrossType::Option(inner) => {
            let inner_rust_ty = extract_inner_type(arg_ty);
            (
                quote! { #arg_id: *mut std::ffi::c_void },
                match &**inner {
                    XrossType::String
                    | XrossType::Object { .. }
                    | XrossType::Option(_)
                    | XrossType::Result { .. }
                    | XrossType::Vec(_)
                    | XrossType::VecDeque(_)
                    | XrossType::LinkedList(_)
                    | XrossType::HashSet(_)
                    | XrossType::BTreeSet(_)
                    | XrossType::BinaryHeap(_)
                    | XrossType::HashMap { .. }
                    | XrossType::BTreeMap { .. } => quote! {
                        let #arg_id = if #arg_id.is_null() { None }
                        else { unsafe { Some(std::ptr::read(#arg_id as *const #inner_rust_ty)) } };
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
        XrossType::Result { ok, err } => {
            let (ok_ty, err_ty) = extract_result_types(arg_ty)
                .unwrap_or_else(|| panic!("Result arg must have concrete Ok/Err types"));
            let gen_read = |ty: &XrossType, ptr: TokenStream, rust_ty: &Type| match ty {
                XrossType::String
                | XrossType::Object { .. }
                | XrossType::Option(_)
                | XrossType::Result { .. }
                | XrossType::Vec(_)
                | XrossType::VecDeque(_)
                | XrossType::LinkedList(_)
                | XrossType::HashSet(_)
                | XrossType::BTreeSet(_)
                | XrossType::BinaryHeap(_)
                | XrossType::HashMap { .. }
                | XrossType::BTreeMap { .. } => quote! { std::ptr::read(#ptr as *const #rust_ty) },
                XrossType::F32 => quote! { f32::from_bits(#ptr as u32) },
                XrossType::F64 => quote! { f64::from_bits(#ptr as u64) },
                _ => quote! { #ptr as usize as #rust_ty },
            };
            let ok_read = gen_read(ok, quote! { #arg_id.ptr }, ok_ty);
            let err_read = gen_read(err, quote! { #arg_id.ptr }, err_ty);
            (
                quote! { #arg_id: xross_core::XrossResult },
                quote! {
                    let #arg_id = if #arg_id.is_ok {
                        Ok(unsafe { #ok_read })
                    } else {
                        Err(unsafe { #err_read })
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
            quote! { Box::into_raw(Box::new(xross_core::XrossString::from(#val_ident))) as *mut std::ffi::c_void }
        }
        XrossType::Object { .. } => {
            quote! { Box::into_raw(Box::new(#val_ident)) as *mut std::ffi::c_void }
        }
        XrossType::Option(_)
        | XrossType::Result { .. }
        | XrossType::Vec(_)
        | XrossType::VecDeque(_)
        | XrossType::LinkedList(_)
        | XrossType::HashSet(_)
        | XrossType::BTreeSet(_)
        | XrossType::BinaryHeap(_)
        | XrossType::HashMap { .. }
        | XrossType::BTreeMap { .. } => {
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
            quote! { xross_core::XrossString },
            quote! { xross_core::XrossString::from(#inner_call) },
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
        XrossType::Option(inner) => {
            let some_ptr_logic = gen_single_value_to_ptr(inner, quote! { val });
            match &**inner {
                XrossType::String
                | XrossType::Object { .. }
                | XrossType::Option(_)
                | XrossType::Result { .. }
                | XrossType::Vec(_)
                | XrossType::VecDeque(_)
                | XrossType::LinkedList(_)
                | XrossType::HashSet(_)
                | XrossType::BTreeSet(_)
                | XrossType::BinaryHeap(_)
                | XrossType::HashMap { .. }
                | XrossType::BTreeMap { .. } => (
                    quote! { *mut std::ffi::c_void },
                    quote! {
                        match #inner_call {
                            Some(val) => #some_ptr_logic,
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
            }
        }
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
        XrossType::VecDeque(_)
        | XrossType::LinkedList(_)
        | XrossType::HashSet(_)
        | XrossType::BTreeSet(_)
        | XrossType::BinaryHeap(_)
        | XrossType::HashMap { .. }
        | XrossType::BTreeMap { .. }
        | XrossType::Vec(_) => (
            quote! { *mut std::ffi::c_void },
            quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
        ),
        _ => {
            if let ReturnType::Type(_, ty) = sig_output {
                let type_str = quote!(#ty).to_string();
                let is_ptr_or_ref = type_str.contains('*') || type_str.contains('&');
                let is_primitive = is_primitive_type(ty);

                if !is_primitive && !is_ptr_or_ref {
                    return (
                        quote! { *mut std::ffi::c_void },
                        quote! { Box::into_raw(Box::new(#inner_call)) as *mut std::ffi::c_void },
                    );
                }
                (quote! { #ty }, quote! { #inner_call })
            } else {
                (quote! { () }, quote! { #inner_call })
            }
        }
    }
}
