//! Renderer-owned texture/coverage resources. World geometry only borrows this owner.
use crate::{arena::Arena, resources::Context, textures::Textures};
use prime_scene::incremental::{ResourceIdentity, SceneInput};
use std::{cell::RefCell, collections::BTreeSet, rc::Rc, sync::Arc};

pub(super) type SharedResources = Rc<RefCell<SceneResources>>;

pub(super) struct SceneResources {
    pub textures: Textures,
    pub templates: Option<crate::omm_cpu::Templates>,
    pub pool: crate::omm::Pool,
    // Shared micromap storage must not reside in a world's geometry arena.
    builds: Arena,
    uploads: Arena,
    identity: ResourceIdentity,
    pub revision: u64,
    pub occlusion_revision: u64,
    pub omm_revision: u64,
    // An explicit prepare may precede rendering. Keep invalidations until Geometry consumes them.
    pub coverage_changed: BTreeSet<u32>,
    // Current support differs from the all-frame OMM proof. Both static and dynamic
    // identities consume this journal; explicit resource prepares must accumulate it.
    pub history_support_changed: BTreeSet<u32>,
    pub prepare_ns: [u64; 3],
    pub preparations: u64,
    work_serial: u64,
}

impl SceneResources {
    pub fn new(context: &Arc<Context>, scene: SceneInput<'_>) -> Result<SharedResources, String> {
        let mut uploads = Arena::new(context, true);
        let textures = Textures::new(context, scene.texture_input(), &mut uploads)?;
        let mut result = Self {
            coverage_changed: textures.coverage_changed.clone(),
            history_support_changed: textures.history_support_changed.clone(),
            textures,
            templates: None,
            pool: crate::omm::Pool::new(),
            builds: Arena::new(context, false),
            uploads,
            identity: scene.resource_identity(),
            revision: 1,
            occlusion_revision: 1,
            omm_revision: 0,
            prepare_ns: [0; 3],
            preparations: 0,
            work_serial: context.retirement_serial(),
        };
        result.prepare_templates(context, true)?;
        Ok(Rc::new(RefCell::new(result)))
    }

    pub fn begin(&mut self, context: &Context, completed: u64) {
        let serial = context.retirement_serial();
        if !context.is_borrowed() || serial != self.work_serial {
            self.prepare_ns = [0; 3];
            self.preparations = 0;
            self.work_serial = serial;
        }
        self.builds.begin(completed, serial);
        self.uploads.begin(completed, serial);
        self.pool.collect(&mut self.builds);
    }

    pub fn prepare(&mut self, context: &Arc<Context>, scene: SceneInput<'_>) -> Result<(), String> {
        let identity_changed = self.identity != scene.resource_identity();
        let changed = self
            .textures
            .update(context, scene.texture_input(), &mut self.uploads)?;
        self.coverage_changed
            .extend(&self.textures.coverage_changed);
        self.history_support_changed
            .extend(&self.textures.history_support_changed);
        self.prepare_templates(context, identity_changed)?;
        if self.textures.occlusion_changed || identity_changed {
            self.occlusion_revision = self
                .occlusion_revision
                .checked_add(1)
                .ok_or("Occlusion resource revision exhausted")?;
        }
        if changed || identity_changed {
            self.revision = self
                .revision
                .checked_add(1)
                .ok_or("GPU resource revision exhausted")?;
        }
        self.identity = scene.resource_identity();
        Ok(())
    }

    fn prepare_templates(
        &mut self,
        context: &Arc<Context>,
        identity_changed: bool,
    ) -> Result<(), String> {
        let Some(limits) = &context.opacity_micromap else {
            return Ok(());
        };
        if !identity_changed
            && self.templates.as_ref().is_some_and(|templates| {
                !templates.dependencies_changed(&self.textures.coverage_changed)
                    && !self.textures.coverage_changed.iter().any(|id| {
                        !templates.contains_texture(*id)
                            && self
                                .textures
                                .source
                                .get(id)
                                .is_some_and(|t| t.region.is_some())
                    })
            })
        {
            return Ok(());
        }
        let started = context.diagnostics_enabled().then(std::time::Instant::now);
        let templates = crate::omm_cpu::Templates::prepare(
            &self.textures.source,
            limits.max_two_state,
            limits.max_four_state,
        );
        if let Some(started) = started {
            self.prepare_ns[0] += started.elapsed().as_nanos() as u64;
            self.preparations += 1;
        }
        let started = context.diagnostics_enabled().then(std::time::Instant::now);
        self.pool.replace(
            context,
            &mut self.builds,
            &mut self.uploads,
            &templates.data,
        )?;
        if let Some(started) = started {
            self.prepare_ns[2] += started.elapsed().as_nanos() as u64;
        }
        self.templates = Some(templates);
        self.omm_revision = self
            .omm_revision
            .checked_add(1)
            .ok_or("OMM resource revision exhausted")?;
        Ok(())
    }

    pub fn collect(&mut self) {
        self.pool.collect(&mut self.builds);
    }
}
