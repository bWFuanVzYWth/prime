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
