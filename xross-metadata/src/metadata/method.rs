mod types;
pub use types::*;

use serde::{Deserialize, Serialize};
use crate::{XrossField, XrossType};
use crate::metadata::ThreadSafety;

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossMethod {
    pub name: String,
    pub symbol: String,
    pub method_type: XrossMethodType,
    pub is_constructor: bool,
    pub args: Vec<XrossField>, // 型を Vec<XrossField> に変更
    pub ret: XrossType,
    pub docs: Vec<String>,
    pub safety: ThreadSafety,
}

