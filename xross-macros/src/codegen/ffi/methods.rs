use crate::codegen::ffi::{gen_arg_conversion, gen_receiver_logic, gen_ret_wrapping};
use crate::utils::extract_safety_attr;
use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::punctuated::Punctuated;
use syn::{FnArg, Pat, ReturnType};
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
    if package.is_empty() { name.to_string() } else { format!("{}.{}", package, name) }
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
