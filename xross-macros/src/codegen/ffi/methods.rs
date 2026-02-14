use crate::codegen::ffi::{gen_arg_conversion, gen_receiver_logic, gen_ret_wrapping};
use crate::utils::extract_safety_attr;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::punctuated::Punctuated;
use syn::{FnArg, Pat, ReturnType};
use xross_metadata::{
    HandleMode, Ownership, ThreadSafety, XrossField, XrossMethod, XrossMethodType, XrossType,
};

/// Data container for FFI method generation.
pub struct MethodFfiData {
    pub symbol_name: String,
    pub export_ident: syn::Ident,
    pub method_type: XrossMethodType,
    pub is_async: bool,
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
            is_async: false,
            args_meta: Vec::new(),
            c_args: Vec::new(),
            call_args: Vec::new(),
            conversion_logic: Vec::new(),
        }
    }
}

/// Builds a full type signature.
pub fn build_signature(package: &str, name: &str) -> String {
    if package.is_empty() { name.to_string() } else { format!("{}.{}", package, name) }
}

/// Generates the actual FFI wrapper function.
pub fn write_ffi_function(
    ffi_data: &MethodFfiData,
    ret_ty: &XrossType,
    sig_output: &ReturnType,
    inner_call: TokenStream,
    handle_mode: HandleMode,
    toks: &mut Vec<TokenStream>,
) {
    if ffi_data.is_async {
        write_async_ffi_function(ffi_data, ret_ty, sig_output, inner_call, toks);
        return;
    }

    let (c_ret_type, wrapper_body) = gen_ret_wrapping(ret_ty, sig_output, inner_call);
    let export_ident = &ffi_data.export_ident;
    let c_args = &ffi_data.c_args;
    let conv_logic = &ffi_data.conversion_logic;

    if handle_mode == HandleMode::Panicable {
        let is_already_result = matches!(ret_ty, XrossType::Result { .. });

        let success_return = if is_already_result {
            quote! { val }
        } else {
            // val is already the FFI-wrapped type (e.g., *mut c_char, i32, etc.)
            // We need to cast it to *mut c_void for XrossResult.ptr
            let ptr_val = match ret_ty {
                XrossType::Void => quote! { std::ptr::null_mut() },
                XrossType::F32 => quote! { val as usize as *mut std::ffi::c_void },
                XrossType::F64 => quote! { val as usize as *mut std::ffi::c_void },
                XrossType::I8
                | XrossType::I16
                | XrossType::I32
                | XrossType::I64
                | XrossType::U16
                | XrossType::ISize
                | XrossType::USize
                | XrossType::Bool => {
                    quote! { val as usize as *mut std::ffi::c_void }
                }
                _ => quote! { val as *mut std::ffi::c_void },
            };
            quote! {
                xross_core::XrossResult { is_ok: true, ptr: #ptr_val }
            }
        };

        let panic_handling = quote! {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                #wrapper_body
            }));

            match result {
                Ok(val) => {
                    #success_return
                }
                Err(panic_err) => {
                    let msg = if let Some(s) = panic_err.downcast_ref::<&str>() {
                        s.to_string()
                    } else if let Some(s) = panic_err.downcast_ref::<String>() {
                        s.clone()
                    } else {
                        "Unknown panic".to_string()
                    };

                    xross_core::XrossResult {
                        is_ok: false,
                        ptr: std::ffi::CString::new(msg).unwrap_or_default().into_raw() as *mut std::ffi::c_void,
                    }
                }
            }
        };

        toks.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> xross_core::XrossResult {
                #(#conv_logic)*
                #panic_handling
            }
        });
    } else {
        toks.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> #c_ret_type {
                #(#conv_logic)*
                #wrapper_body
            }
        });
    }
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
                let xross_ty = crate::types::resolver::resolve_type_with_attr(
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

                let (c_arg, conv, call_arg) =
                    gen_arg_conversion(&pat_type.ty, &arg_ident, &xross_ty);
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
        handle_mode: HandleMode::Normal,
        is_constructor: false,
        is_async: false,
        args: vec![],
        ret: XrossType::Object {
            signature: build_signature(package, name),
            ownership: Ownership::Owned,
        },
        safety: ThreadSafety::Lock,
        docs: vec!["Creates a clone of the native object.".to_string()],
    });
}

pub fn write_async_ffi_function(
    ffi_data: &MethodFfiData,
    ret_ty: &XrossType,
    _sig_output: &ReturnType,
    inner_call: TokenStream,
    toks: &mut Vec<TokenStream>,
) {
    let export_ident = &ffi_data.export_ident;
    let c_args = &ffi_data.c_args;
    let conv_logic = &ffi_data.conversion_logic;

    let res_mapper = match ret_ty {
        XrossType::Void => {
            quote! { |_| xross_core::XrossResult { is_ok: true, ptr: std::ptr::null_mut() } }
        }
        _ => {
            let ptr_logic = super::conversion::gen_single_value_to_ptr(ret_ty, quote! { val });
            quote! { |val| xross_core::XrossResult { is_ok: true, ptr: #ptr_logic } }
        }
    };

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #export_ident(#(#c_args),*) -> xross_core::XrossTask {
            #(#conv_logic)*
            xross_core::xross_spawn_task(#inner_call, #res_mapper)
        }
    });
}
