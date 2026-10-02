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
    workers: Option<std::sync::Arc<crate::workers::CpuWorkers>>,
}
impl SourceRoutes {
    #[cfg(not(test))]
    pub(crate) fn with_workers(workers: std::sync::Arc<crate::workers::CpuWorkers>) -> Self {
        Self {
            workers: Some(workers),
        }
    }
    pub(crate) fn workers(&self) -> Option<&std::sync::Arc<crate::workers::CpuWorkers>> {
        self.workers.as_ref()
    }
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
