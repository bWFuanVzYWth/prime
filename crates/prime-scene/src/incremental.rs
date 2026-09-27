//! Owner-thread publication and immutable renderer inputs. Dirty sets describe net state,
//! not a queue of work: repeated edits to one identity occupy one entry.
use crate::{
    scene::{Mesh, MeshKey, Scene, SceneMesh, SourceScene},
    spatial::Cell,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    ops::Deref,
    sync::atomic::{AtomicU64, Ordering},
};

/// Identifies an explicit owner across moves. Allocated once per context; never
/// used to schedule work or mutated during publication/consumption.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ContextId(u64);

impl Default for ContextId {
    #[allow(deprecated)] // fetch_update is the spelling supported by the workspace MSRV.
    fn default() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        Self(
            NEXT.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
                .expect("Scene context identities exhausted"),
        )
    }
}

/// Texture publication identity cannot be substituted for a geometry cursor.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct TextureCursor {
    owner: ContextId,
    epoch: u64,
    generation: u64,
}

#[derive(Default)]
struct TextureChanges {
    from: TextureCursor,
    current: TextureCursor,
    changed: BTreeSet<u32>,
    reset: bool,
}

#[derive(Clone, Copy)]
pub struct TextureInput<'a> {
    source: &'a BTreeMap<u32, crate::scene::Texture>,
    changes: Option<&'a TextureChanges>,
}

impl<'a> From<&'a BTreeMap<u32, crate::scene::Texture>> for TextureInput<'a> {
    fn from(source: &'a BTreeMap<u32, crate::scene::Texture>) -> Self {
        Self {
            source,
            changes: None,
        }
    }
}

/// The only permitted worksets are an unchanged input, a certified set of touched
/// identities, or a full resynchronization. None means a diagnostic snapshot cursor.
impl<'a> TextureInput<'a> {
    pub fn updates(
        self,
        cursor: Option<TextureCursor>,
    ) -> (Option<TextureCursor>, TextureUpdates<'a>) {
        let keys = match self.changes {
            Some(changes) if cursor == Some(changes.current) => TextureKeys::None,
            Some(changes) if !changes.reset && cursor == Some(changes.from) => {
                TextureKeys::Changed(changes.changed.iter())
            }
            _ => TextureKeys::All(self.source.iter()),
        };
        (
            self.changes.map(|changes| changes.current),
            TextureUpdates {
                source: self.source,
                keys,
            },
        )
    }
}

pub struct TextureUpdates<'a> {
    source: &'a BTreeMap<u32, crate::scene::Texture>,
    keys: TextureKeys<'a>,
}

enum TextureKeys<'a> {
    None,
    All(std::collections::btree_map::Iter<'a, u32, crate::scene::Texture>),
    Changed(std::collections::btree_set::Iter<'a, u32>),
}

impl TextureUpdates<'_> {
    pub fn is_snapshot(&self) -> bool {
        matches!(self.keys, TextureKeys::All(_))
    }

    /// A full snapshot can revoke diagnostic texture identities; deltas contain
    /// only touched resident pixels. Resource retirement has its own lifetime.
    pub fn retains(&self, id: u32) -> bool {
        !self.is_snapshot() || self.source.contains_key(&id)
    }
}

impl<'a> Iterator for TextureUpdates<'a> {
    type Item = (&'a u32, Option<&'a crate::scene::Texture>);
    fn next(&mut self) -> Option<Self::Item> {
        match &mut self.keys {
            TextureKeys::None => None,
            TextureKeys::All(all) => all.next().map(|(id, t)| (id, Some(t))),
            TextureKeys::Changed(keys) => keys.next().map(|key| (key, self.source.get(key))),
        }
    }
}

/// A content cursor, deliberately unrelated to a source packet sequence or GPU serial.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct TerrainGeneration {
    owner: ContextId,
    value: u64,
}

impl TerrainGeneration {
    pub(crate) fn next(self) -> Result<Self, String> {
        self.value
            .checked_add(1)
            .map(|value| Self { value, ..self })
            .ok_or_else(|| "Terrain generation exhausted".into())
    }
}

/// Only protocol commit code can publish invalidations. Failed decoding has no access
/// to the downstream cursor and cannot acknowledge or discard accepted changes.
#[derive(Default)]
pub(crate) struct SourceEdits {
    pub base: u64,
    pub meshes: BTreeSet<MeshKey>,
    pub availability: BTreeSet<Cell>,
    pub textures: BTreeSet<u32>,
}

impl SourceEdits {
    pub fn availability(&mut self, origin: [f64; 3]) {
        self.availability
            .insert(Cell::containing(origin).expect("validated source origin"));
    }

    fn acknowledge(&mut self, revision: u64) {
        self.meshes.clear();
        self.availability.clear();
        self.textures.clear();
        self.base = revision;
    }
}

#[derive(Default)]
pub(crate) struct TerrainIndex {
    pub source: Option<ContextId>,
    /// Incomplete cells retain membership without generating renderer geometry.
    pub cells: BTreeMap<Cell, BTreeSet<MeshKey>>,
    pub generation: TerrainGeneration,
    pub from: TerrainGeneration,
    pub changed: BTreeSet<Cell>,
    pub reset: bool,
}

impl TerrainIndex {
    fn insert(&mut self, key: MeshKey, mesh: &SceneMesh) {
        if !mesh.triangles.is_empty() {
            let cell = Cell::containing(mesh.origin).expect("validated source origin");
            self.cells.entry(cell).or_default().insert(key);
            self.changed.insert(cell);
        }
    }

    fn remove(&mut self, key: MeshKey, mesh: &SceneMesh) {
        if !mesh.triangles.is_empty() {
            let cell = Cell::containing(mesh.origin).expect("validated source origin");
            let members = self.cells.get_mut(&cell).expect("indexed mesh");
            members.remove(&key);
            if members.is_empty() {
                self.cells.remove(&cell);
            }
            self.changed.insert(cell);
        }
    }
}

/// A measured work count, independent of timers/allocator instrumentation.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct TranslationWork {
    pub meshes_validated: usize,
    pub meshes_published: usize,
    pub textures_published: usize,
    pub snapshot_changed: bool,
    pub dynamic_changed: bool,
}

/// Persistent, sealed translation state. Renderer code cannot mutate a mesh behind
/// its content cursor. Full mutable Scene values remain explicit diagnostic inputs.
///
/// ```compile_fail
/// use prime_scene::incremental::TranslatedScene;
/// let mut translated = TranslatedScene::default();
/// translated.input().meshes.clear(); // No mutable access to published source state.
/// ```
#[derive(Default)]
pub struct TranslatedScene {
    scene: Scene,
    source_revision: Option<u64>,
    source_id: Option<ContextId>,
    terrain: TerrainIndex,
    textures: TextureChanges,
}

/// Read-only frame input. Only TranslatedScene can create the incremental variant;
/// arbitrary public Scene fixtures always use the validating snapshot path.
#[derive(Clone, Copy)]
pub struct SceneInput<'a> {
    scene: &'a Scene,
    terrain: Option<&'a TerrainIndex>,
    textures: Option<&'a TextureChanges>,
}

/// A renderer cache key for a static/texture publication. The owner is part of
/// the identity: equal numeric revisions from different contexts never alias.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ScenePublication {
    owner: Option<ContextId>,
    source: Option<ContextId>,
    epoch: u64,
    revision: u64,
}

impl ScenePublication {
    pub fn same_owner(self, other: Self) -> bool {
        self.owner == other.owner && self.source == other.source
    }
}

impl<'a> SceneInput<'a> {
    pub fn publication(self) -> ScenePublication {
        ScenePublication {
            owner: self.terrain.map(|index| index.generation.owner),
            source: self.terrain.and_then(|index| index.source),
            epoch: self.scene.epoch,
            revision: self.scene.revision,
        }
    }

    pub(crate) fn terrain(self) -> Option<&'a TerrainIndex> {
        self.terrain
    }

    pub fn texture_input(self) -> TextureInput<'a> {
        TextureInput {
            source: &self.scene.textures,
            changes: self.textures,
        }
    }
}

impl Deref for SceneInput<'_> {
    type Target = Scene;
    fn deref(&self) -> &Scene {
        self.scene
    }
}

impl<'a> From<&'a Scene> for SceneInput<'a> {
    fn from(scene: &'a Scene) -> Self {
        Self {
            scene,
            terrain: None,
            textures: None,
        }
    }
}

impl<'a> From<&'a TranslatedScene> for SceneInput<'a> {
    fn from(scene: &'a TranslatedScene) -> Self {
        scene.input()
    }
}

impl TranslatedScene {
    pub fn input(&self) -> SceneInput<'_> {
        SceneInput {
            scene: &self.scene,
            terrain: Some(&self.terrain),
            textures: Some(&self.textures),
        }
    }

    /// Synchronous validation followed by publication and source acknowledgement.
    /// On error the previous frame and all accepted source invalidations survive.
    /// A new consumer or a missed source batch reconstructs from the resident state.
    pub fn update(
        &mut self,
        source: &mut SourceScene,
        anchor: [f64; 3],
    ) -> Result<TranslationWork, String> {
        if anchor.iter().any(|value| !value.is_finite()) {
            return Err("Invalid scene anchor".into());
        }
        source.collect_textures()?;
        source.instances.publish();
        let reset = self.source_id != Some(source.id) || self.scene.epoch != source.epoch;
        let changed = reset || self.source_revision != Some(source.revision);
        let resync = reset || (changed && self.source_revision != Some(source.edits.base));
        let rebase = reset || self.scene.anchor != anchor;
        let dynamic_changed = reset || self.scene.dynamic.revision != source.dynamic_revision();
        let mut work = TranslationWork {
            snapshot_changed: changed || rebase,
            dynamic_changed,
            ..Default::default()
        };
        if !work.snapshot_changed && !dynamic_changed {
            return Ok(work);
        }

        // Coordinate validity depends on the anchor; ordinary source edits validate
        // only touched meshes. No source or consumer state changes during this phase.
        if resync || rebase {
            for mesh in source.meshes.values() {
                validate_mesh(source, mesh, anchor)?;
                work.meshes_validated += 1;
            }
        } else if changed {
            for key in &source.edits.meshes {
                if let Some(mesh) = source.meshes.get(key) {
                    validate_mesh(source, mesh, anchor)?;
                    work.meshes_validated += 1;
                }
            }
        }
        let dynamic = if dynamic_changed || rebase {
            Some(source.translate_dynamic(anchor)?)
        } else {
            None
        };
        let revision = if work.snapshot_changed {
            self.scene
                .revision
                .checked_add(1)
                .ok_or("Translation revision exhausted")?
        } else {
            self.scene.revision
        };
        let terrain_changed = resync
            || (changed
                && (!source.edits.meshes.is_empty() || !source.edits.availability.is_empty()));
        let generation = if terrain_changed {
            self.terrain.generation.next()?
        } else {
            self.terrain.generation
        };
        let textures_changed = resync || (changed && !source.edits.textures.is_empty());
        let texture_generation = if textures_changed {
            self.textures
                .current
                .generation
                .checked_add(1)
                .ok_or("Texture generation exhausted")?
        } else {
            self.textures.current.generation
        };

        if textures_changed {
            self.textures.from = self.textures.current;
            self.textures.current = TextureCursor {
                owner: self.textures.current.owner,
                epoch: source.epoch,
                generation: texture_generation,
            };
            self.textures.reset = resync;
            self.textures.changed.clone_from(&source.edits.textures);
        }
        if terrain_changed {
            self.terrain.source = Some(source.id);
            self.terrain.from = self.terrain.generation;
            self.terrain.generation = generation;
            self.terrain.changed.clear();
            self.terrain.reset = resync;
        }
        if resync {
            self.scene.meshes.clear();
            self.terrain.cells.clear();
            for (&key, mesh) in &source.meshes {
                if mesh.triangles.is_empty() {
                    continue;
                }
                let mesh = scene_mesh(mesh);
                self.terrain.insert(key, &mesh);
                self.scene.meshes.insert(key, mesh);
                work.meshes_published += 1;
            }
            self.scene.ready_terrain.clone_from(&source.sections.ready);
            self.scene.textures.clone_from(&source.textures);
            work.textures_published = source.textures.len();
        } else if changed {
            for &key in &source.edits.meshes {
                if let Some(old) = self.scene.meshes.remove(&key) {
                    self.terrain.remove(key, &old);
                }
                if let Some(mesh) = source
                    .meshes
                    .get(&key)
                    .filter(|mesh| !mesh.triangles.is_empty())
                {
                    let mesh = scene_mesh(mesh);
                    self.terrain.insert(key, &mesh);
                    self.scene.meshes.insert(key, mesh);
                    work.meshes_published += 1;
                }
            }
            for &cell in &source.edits.availability {
                if source.sections.ready.contains(&cell) {
                    self.scene.ready_terrain.insert(cell);
                } else {
                    self.scene.ready_terrain.remove(&cell);
                }
                self.terrain.changed.insert(cell);
            }
            for &key in &source.edits.textures {
                if let Some(texture) = source.textures.get(&key) {
                    self.scene.textures.insert(key, texture.clone());
                } else {
                    self.scene.textures.remove(&key);
                }
                work.textures_published += 1;
            }
        }
        self.scene.epoch = source.epoch;
        self.scene.revision = revision;
        self.scene.anchor = anchor;
        if let Some(dynamic) = dynamic {
            self.scene.dynamic = dynamic;
        }
        self.source_revision = Some(source.revision);
        self.source_id = Some(source.id);
        source.edits.acknowledge(source.revision);
        Ok(work)
    }
}

fn validate_mesh(source: &SourceScene, mesh: &Mesh, anchor: [f64; 3]) -> Result<(), String> {
    if mesh.triangles.is_empty() {
        return Ok(());
    }
    if mesh.texture_id != 0 && !source.textures.contains_key(&mesh.texture_id) {
        return Err("mesh references a texture that has not been captured".into());
    }
    for bound in mesh.bounds {
        for i in 0..3 {
            if (mesh.origin[i] - anchor[i] + f64::from(bound[i])).abs() > 1_048_576.0 {
                return Err("scene exceeds the supported camera-relative coordinate range".into());
            }
        }
    }
    Ok(())
}

fn scene_mesh(mesh: &Mesh) -> SceneMesh {
    SceneMesh {
        revision: mesh.revision.number(),
        flags: mesh.flags,
        origin: mesh.origin,
        triangles: mesh.triangles.clone(),
    }
}

#[cfg(test)]
#[path = "incremental_tests.rs"]
mod tests;
