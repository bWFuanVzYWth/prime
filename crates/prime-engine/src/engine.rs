//! Owns one source scene and its translated/GPU state. The FFI confines it to one OS thread.
use prime_scene::{
    protocol::Frame,
    scene::{Scene, SourceScene},
};

#[derive(Default)]
pub(crate) struct Engine {
    pub(crate) source: SourceScene,
    translated: Option<(u64, [f64; 3], Scene)>,
    translation_revision: u64,
    pub(crate) failed: bool,
    #[cfg(feature = "vulkan")]
    pub(crate) renderer: Option<prime_vulkan::Renderer>,
}

impl Engine {
    fn prepare(&mut self, frame: &Frame) -> Result<[f64; 3], String> {
        if self.failed {
            return Err("renderer failed; destroy and recreate the session".into());
        }
        if frame.epoch != self.source.epoch || frame.epoch == 0 {
            return Err("frame resource epoch mismatch".into());
        }
        let anchor = frame.anchor();
        if self
            .translated
            .as_ref()
            .is_none_or(|(revision, origin, _)| {
                *revision != self.source.revision || *origin != anchor
            })
        {
            let mut translated = self.source.translate(anchor)?;
            self.translation_revision = self
                .translation_revision
                .checked_add(1)
                .ok_or("translation revision exhausted")?;
            translated.revision = self.translation_revision;
            self.translated = Some((self.source.revision, anchor, translated));
        }
        let scene = &mut self.translated.as_mut().unwrap().2;
        if scene.dynamic.revision != self.source.dynamic_revision() {
            scene.dynamic = self.source.translate_dynamic(anchor)?;
        }
        Ok(anchor)
    }

    pub(crate) fn render(&mut self, frame: &Frame) -> Result<Vec<u8>, String> {
        let anchor = self.prepare(frame)?;
        #[cfg(feature = "vulkan")]
        {
            poison_on_failure(&mut self.failed, || {
                if self.renderer.is_none() {
                    self.renderer = Some(prime_vulkan::Renderer::new()?);
                }
                self.renderer.as_mut().unwrap().render(
                    &self.translated.as_ref().unwrap().2,
                    &frame.relative_camera(anchor),
                    frame.width,
                    frame.height,
                    frame.sample_index,
                )
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
        let anchor = self.prepare(frame)?;
        poison_on_failure(&mut self.failed, || {
            let renderer = self
                .renderer
                .as_mut()
                .ok_or("Attach a Vulkan host before recording")?;
            unsafe {
                renderer.record_host(
                    &self.translated.as_ref().unwrap().2,
                    &frame.relative_camera(anchor),
                    frame.width,
                    frame.height,
                    frame.sample_index,
                    command,
                    image,
                    view,
                    serial,
                )
            }
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
        assert_eq!(scene.dynamic.origin, [-512.0, 0.0, 0.0]);
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
}
