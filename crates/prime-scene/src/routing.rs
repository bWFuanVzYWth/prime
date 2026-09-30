//! Dynamic billboards are the only remaining production appearance route.
#[cfg(test)]
mod legacy;
#[cfg(test)]
use legacy::EMPTY_TRIANGLE;
#[cfg(test)]
pub(crate) use legacy::SourceRoutes;
#[cfg(not(test))]
#[derive(Default)]
pub(crate) struct SourceRoutes {
    workers: Option<crate::workers::CpuWorkers>,
}
#[cfg(not(test))]
const EMPTY_TRIANGLE: crate::Triangle = crate::Triangle {
    positions: [[0.; 3]; 3],
    colors: [[0.; 4]; 3],
    uvs: [[0.; 2]; 3],
    texture_id: 1,
    flags: 0,
};
mod particles;
