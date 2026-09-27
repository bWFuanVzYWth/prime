//! Owns one source scene and its translated/GPU state. The FFI confines it to one OS thread.
use crate::cpu_profile::{PrepareProfile, elapsed};
use prime_scene::{
    protocol::Frame,
    scene::{Scene, SourceScene},
    settings::{RenderMode, RenderSettings},
};

#[derive(Default)]
pub(crate) struct Engine {
    pub(crate) source: SourceScene,
    translated: Option<(u64, [f64; 3], Scene)>,
    translation_revision: u64,
    cpu_profile: PrepareProfile,
    pub(crate) failed: bool,
    pub(crate) settings: RenderSettings,
    last_frame: Option<(Frame, RenderSettings)>,
    frozen_frame: Option<(Frame, RenderSettings)>,
    #[cfg(feature = "vulkan")]
    pub(crate) renderer: Option<prime_vulkan::Renderer>,
}

impl Engine {
    pub(crate) fn submit(&mut self, bytes: &[u8]) -> Result<(), String> {
        if self.failed {
            return Err("Renderer session is poisoned".into());
        }
        if self.frozen_frame.is_some() {
            return Err("Cannot mutate a frozen offline scene".into());
        }
        self.source.submit(bytes)?;
        // A scene mutation not yet rendered cannot be called the last displayed snapshot.
        self.last_frame = None;
        Ok(())
    }

    pub(crate) fn configure(&mut self, settings: RenderSettings) -> Result<(), String> {
        settings.validate()?;
        if self.failed {
            return Err("Renderer session is poisoned".into());
        }
        let frozen = if settings.mode == RenderMode::Offline {
            Some(match self.frozen_frame {
                Some(snapshot) => snapshot,
                None => self
                    .last_frame
                    .ok_or("Offline rendering needs a successfully recorded world frame")?,
            })
        } else {
            None
        };
        #[cfg(feature = "vulkan")]
        if let Some(renderer) = &mut self.renderer {
            let mut active = settings;
            if let Some((_, transport)) = frozen {
                active.bounces = transport.bounces;
                active.sun = transport.sun;
                active.sky = transport.sky;
                active.seed = transport.seed;
            }
            poison_on_failure(&mut self.failed, || renderer.configure(active))?;
            renderer.set_scene_frozen(frozen.is_some());
        }
        self.settings = settings;
        self.frozen_frame = frozen;
        Ok(())
    }

    fn effective_frame(&self, frame: &Frame) -> Frame {
        if let Some((fixed, _)) = self.frozen_frame {
            // Resize changes only the image extent/aspect. Pose, FOV, epoch and lighting stay frozen.
            Frame {
                width: frame.width,
                height: frame.height,
                sample_index: frame.sample_index,
                ..fixed
            }
        } else {
            *frame
        }
    }

    fn prepare(&mut self, frame: &Frame) -> Result<[f64; 3], String> {
        let started = self.cpu_profile.start();
        if self.failed {
            return Err("renderer failed; destroy and recreate the session".into());
        }
        if frame.epoch != self.source.epoch || frame.epoch == 0 {
            return Err("frame resource epoch mismatch".into());
        }
        let anchor = frame.anchor();
        let mut snapshot_ns = 0;
        let mut dynamic_ns = 0;
        let mut snapshot_changed = false;
        let mut dynamic_changed = false;
        if self
            .translated
            .as_ref()
            .is_none_or(|(revision, origin, _)| {
                *revision != self.source.revision || *origin != anchor
            })
        {
            let snapshot_start = self.cpu_profile.start();
            let mut translated = self.source.translate(anchor)?;
            self.translation_revision = self
                .translation_revision
                .checked_add(1)
                .ok_or("translation revision exhausted")?;
            translated.revision = self.translation_revision;
            self.translated = Some((self.source.revision, anchor, translated));
            snapshot_ns = elapsed(snapshot_start);
            snapshot_changed = true;
        }
        let scene = &mut self.translated.as_mut().unwrap().2;
        if scene.dynamic.revision != self.source.dynamic_revision() {
            let dynamic_start = self.cpu_profile.start();
            scene.dynamic = self.source.translate_dynamic(anchor)?;
            dynamic_ns = elapsed(dynamic_start);
            dynamic_changed = true;
        }
        self.cpu_profile.observe(
            started,
            snapshot_ns,
            dynamic_ns,
            snapshot_changed,
            dynamic_changed,
        );
        Ok(anchor)
    }

    pub(crate) fn render(&mut self, frame: &Frame) -> Result<Vec<u8>, String> {
        let effective = self.effective_frame(frame);
        let frame = &effective;
        let anchor = self.prepare(frame)?;
        #[cfg(feature = "vulkan")]
        {
            poison_on_failure(&mut self.failed, || {
                if self.renderer.is_none() {
                    let mut renderer = prime_vulkan::Renderer::with_mode(self.settings.mode)?;
                    renderer.configure(self.settings)?;
                    self.renderer = Some(renderer);
                }
                let result = self.renderer.as_mut().unwrap().render_with_instances(
                    &self.translated.as_ref().unwrap().2,
                    self.source.instances(),
                    &frame.relative_camera(anchor),
                    frame.width,
                    frame.height,
                    frame.sample_index,
                )?;
                self.last_frame = Some((
                    *frame,
                    self.frozen_frame.map_or(self.settings, |(_, fixed)| fixed),
                ));
                Ok(result)
            })
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = anchor;
            Err("this native library was built without the vulkan feature".into())
        }
    }

    #[cfg(feature = "vulkan")]
    pub(crate) unsafe fn record(
        &mut self,
        frame: &Frame,
        command: u64,
        image: u64,
        view: u64,
        serial: u64,
    ) -> Result<(), String> {
        let effective = self.effective_frame(frame);
        let frame = &effective;
        let anchor = self.prepare(frame)?;
        poison_on_failure(&mut self.failed, || {
            let renderer = self
                .renderer
                .as_mut()
                .ok_or("Attach a Vulkan host before recording")?;
            unsafe {
                renderer.record_host_with_instances(
                    &self.translated.as_ref().unwrap().2,
                    self.source.instances(),
                    &frame.relative_camera(anchor),
                    frame.width,
                    frame.height,
                    frame.sample_index,
                    command,
                    image,
                    view,
                    serial,
                )?;
            }
            self.last_frame = Some((
                *frame,
                self.frozen_frame.map_or(self.settings, |(_, fixed)| fixed),
            ));
            Ok(())
        })
    }
}

#[cfg(any(feature = "vulkan", test))]
pub(crate) fn poison_on_failure<T>(
    failed: &mut bool,
    work: impl FnOnce() -> Result<T, String>,
) -> Result<T, String> {
    if *failed {
        return Err("renderer session is poisoned".into());
    }
    // Set before entering external GPU code so unwinding also retires the session.
    *failed = true;
    let result = work();
    if result.is_ok() {
        *failed = false;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::{Camera, protocol::MAGIC};
    use std::sync::Arc;

    fn header(operation: u32) -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in [MAGIC, 1, operation, 0] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend(1_u64.to_le_bytes());
        bytes
    }

    fn vertices(bytes: &mut Vec<u8>) {
        for xyz in [
            [0_f32, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [1.0, 1.0, 0.0],
            [0.0, 1.0, 0.0],
        ] {
            for value in xyz {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend([255; 4]);
            bytes.extend(0_f32.to_le_bytes());
            bytes.extend(0_f32.to_le_bytes());
        }
    }

    fn dynamic(sequence: u64, populated: bool) -> Vec<u8> {
        let mut bytes = header(6);
        bytes.extend(sequence.to_le_bytes());
        for value in [0_f64; 3] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend(u32::from(populated).to_le_bytes());
        bytes.extend(0_u32.to_le_bytes());
        if populated {
            for value in [0_u32, 2, 4, 4, 24, 0, 12, 16] {
                bytes.extend(value.to_le_bytes());
            }
            vertices(&mut bytes);
        }
        bytes
    }

    #[test]
    fn dynamic_publication_reuses_the_static_snapshot_and_rebase_shares_vertices() {
        let mut engine = Engine::default();
        engine.source.submit(&header(1)).unwrap();
        let mut mesh = header(2);
        mesh.extend(91_u64.to_le_bytes());
        mesh.extend(1_u64.to_le_bytes());
        for value in [0_f64; 3] {
            mesh.extend(value.to_le_bytes());
        }
        for value in [4_u32, 24, 0, 12, 16, 4, 0, 0, 0, 0] {
            mesh.extend(value.to_le_bytes());
        }
        vertices(&mut mesh);
        engine.source.submit(&mesh).unwrap();
        let mut frame = Frame {
            epoch: 1,
            world_position: [0.0; 3],
            camera: Camera {
                position: [0.0; 3],
                forward: [0.0, 0.0, -1.0],
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                vertical_fov_radians: 1.0,
            },
            width: 1920,
            height: 1080,
            sample_index: 0,
        };
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        let static_node = std::ptr::from_ref(&scene.meshes[&(91, 0)]);
        let static_vertices = scene.meshes[&(91, 0)].triangles.clone();
        let translation_revision = engine.translation_revision;
        engine.source.submit(&dynamic(1, true)).unwrap();
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        assert_eq!(std::ptr::from_ref(&scene.meshes[&(91, 0)]), static_node);
        assert_eq!(engine.translation_revision, translation_revision);
        assert_eq!(scene.dynamic.revision, 1);
        assert_eq!(scene.dynamic.triangles.len(), 2);
        let dynamic_vertices = scene.dynamic.triangles.clone();

        frame.world_position[0] = 512.0;
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        assert_eq!(engine.translation_revision, translation_revision + 1);
        assert_eq!(scene.dynamic.origin, [0.0; 3]);
        assert!(Arc::ptr_eq(&dynamic_vertices, &scene.dynamic.triangles));
        assert!(Arc::ptr_eq(
            &static_vertices,
            &scene.meshes[&(91, 0)].triangles
        ));
        let static_node = std::ptr::from_ref(&scene.meshes[&(91, 0)]);

        engine.source.submit(&dynamic(2, false)).unwrap();
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        assert_eq!(engine.translation_revision, translation_revision + 1);
        assert_eq!(std::ptr::from_ref(&scene.meshes[&(91, 0)]), static_node);
        assert_eq!(scene.dynamic.revision, 2);
        assert!(scene.dynamic.triangles.is_empty());
        assert_eq!(dynamic_vertices.len(), 2);
    }

    #[test]
    fn identical_complete_section_recompile_keeps_translated_snapshot_and_mesh_generation() {
        fn section(sequence: u64, red: u8) -> Vec<u8> {
            let mut bytes = header(8);
            bytes.extend(91_u64.to_le_bytes());
            bytes.extend(sequence.to_le_bytes());
            for value in [0_f64; 3] {
                bytes.extend(value.to_le_bytes());
            }
            bytes.extend(2_u32.to_le_bytes());
            bytes.extend(0_u32.to_le_bytes());
            for layer in 0..2_u32 {
                for value in [layer, 0, layer, 4, 4, 24, 0, 12, 16, 0] {
                    bytes.extend(value.to_le_bytes());
                }
                let start = bytes.len();
                vertices(&mut bytes);
                if layer == 0 {
                    bytes[start + 12] = red;
                }
            }
            bytes
        }
        let mut engine = Engine::default();
        engine.source.submit(&header(1)).unwrap();
        engine.source.submit(&section(1, 255)).unwrap();
        let frame = Frame {
            epoch: 1,
            world_position: [0.0; 3],
            camera: Camera {
                position: [0.0; 3],
                forward: [0.0, 0.0, -1.0],
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                vertical_fov_radians: 1.0,
            },
            width: 1920,
            height: 1080,
            sample_index: 0,
        };
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        let before_revision = engine.translation_revision;
        let before_node = std::ptr::from_ref(&scene.meshes[&(91, 0)]);
        let first = scene.meshes[&(91, 0)].triangles.clone();
        let other = scene.meshes[&(91, 1)].triangles.clone();
        engine.source.submit(&section(2, 255)).unwrap();
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        assert_eq!(engine.translation_revision, before_revision);
        assert_eq!(std::ptr::from_ref(&scene.meshes[&(91, 0)]), before_node);
        assert_eq!(scene.meshes[&(91, 0)].revision, 1);
        assert!(Arc::ptr_eq(&first, &scene.meshes[&(91, 0)].triangles));
        engine.source.submit(&section(3, 128)).unwrap();
        engine.prepare(&frame).unwrap();
        let scene = &engine.translated.as_ref().unwrap().2;
        assert_eq!(engine.translation_revision, before_revision + 1);
        assert_eq!(scene.meshes[&(91, 0)].revision, 3);
        assert!(!Arc::ptr_eq(&first, &scene.meshes[&(91, 0)].triangles));
        assert_eq!(scene.meshes[&(91, 1)].revision, 1);
        assert!(Arc::ptr_eq(&other, &scene.meshes[&(91, 1)].triangles));
    }
    #[test]
    fn frozen_scene_uses_last_recorded_pose_and_transport_until_explicit_thaw() {
        let mut engine = Engine::default();
        let offline = RenderSettings {
            mode: RenderMode::Offline,
            ..Default::default()
        };
        assert!(engine.configure(offline).is_err());
        assert!(!engine.failed);
        engine.submit(&header(1)).unwrap();
        let frame = Frame {
            epoch: 1,
            world_position: [512.25, -20.5, 8192.75],
            camera: Camera {
                position: [0.0; 3],
                forward: [0.0, 0.0, -1.0],
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                vertical_fov_radians: 1.1,
            },
            width: 1920,
            height: 1080,
            sample_index: 9,
        };
        engine.last_frame = Some((frame, engine.settings));
        engine.configure(offline).unwrap();
        assert!(engine.submit(&dynamic(1, true)).is_err());
        assert!(engine.submit(&header(1)).is_err());
        let mut moved = frame;
        moved.world_position = [-9999.0; 3];
        moved.camera.vertical_fov_radians = 0.5;
        moved.epoch = 20;
        moved.width = 701;
        moved.height = 999;
        moved.sample_index = 7;
        let effective = engine.effective_frame(&moved);
        assert_eq!(effective.world_position, frame.world_position);
        assert_eq!(effective.camera, frame.camera);
        assert_eq!(effective.epoch, 1);
        assert_eq!(
            (effective.width, effective.height, effective.sample_index),
            (701, 999, 7)
        );
        engine
            .configure(RenderSettings {
                exposure: 2.0,
                bounces: 20,
                sky: 3.0,
                ..offline
            })
            .unwrap();
        assert_eq!(engine.settings.exposure, 2.0);
        assert_eq!(engine.frozen_frame.unwrap().1, RenderSettings::default());
        engine.configure(RenderSettings::default()).unwrap();
        assert!(engine.frozen_frame.is_none());
        assert_eq!(
            engine.effective_frame(&moved).world_position,
            moved.world_position
        );
        engine.submit(&dynamic(1, true)).unwrap();
        assert!(
            engine.configure(offline).is_err(),
            "Unrecorded source mutations cannot freeze the previous frame"
        );
    }
}
