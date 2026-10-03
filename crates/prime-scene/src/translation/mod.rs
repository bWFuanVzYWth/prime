//! CPU batch ownership and placement. Executors consume geometry ranges and transforms,
//! never source section coordinates, grid sizes, or capture callback semantics.

mod objects;
mod placements;
mod terrain;

pub use objects::{GeometryUpdate, ObjectKey, Placement, Planner, ScenePlan};
pub use placements::PlacementChange;
pub use terrain::{
    TerrainGeometry, TerrainLimits, TerrainMember, TerrainPlan, TerrainPlanner, TerrainUpdate,
};

pub(crate) fn translation([x, y, z]: [f32; 3]) -> [f32; 12] {
    [1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z]
}

/// Resource limits supplied by the executor; they do not change the global grid.
#[derive(Clone, Copy)]
pub struct BatchLimits {
    pub triangles: u32,
    pub placements: u32,
}

impl BatchLimits {
    pub fn validate(self) -> Result<(), String> {
        if self.triangles == 0 || self.placements == 0 {
            return Err("Batch limits must be nonzero".into());
        }
        Ok(())
    }
}
