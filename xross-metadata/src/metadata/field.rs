use crate::XrossType;
use crate::metadata::ThreadSafety;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossField {
    pub name: String,
    pub ty: XrossType,
    pub docs: Vec<String>,
    pub safety: ThreadSafety,
}
