use serde::{Deserialize, Serialize};
use crate::XrossType;

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct XrossField {
    pub name: String,
    pub ty: XrossType,
    pub docs: Vec<String>,
}
