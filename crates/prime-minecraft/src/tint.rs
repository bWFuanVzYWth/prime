//! Closed geometry can await explicit host color results, but cannot query the host itself.
use prime_scene::Triangle;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Request {
    pub position: [i32; 3],
    pub state: u32,
    /// -1 is the actual fluid material source; nonnegative values are block tint slots.
    pub slot: i32,
}
struct Patch {
    request: usize,
    layer: usize,
    start: usize,
    end: usize,
}
#[derive(Default)]
pub(crate) struct Deferred {
    pub requests: Vec<Request>,
    patches: Vec<Patch>,
    current: [i32; 3],
    state: u32,
    first: usize,
}
impl Deferred {
    pub fn begin(&mut self, state: u32, position: [i32; 3]) {
        self.current = position;
        self.state = state;
        self.first = self.requests.len();
    }
    pub fn patch(&mut self, slot: i32, layer: usize, start: usize, end: usize) {
        if start == end {
            return;
        }
        // A block normally has one slot. No hash map or allocation per block/quad, and no
        // callback replay when several selected multipart children use the same source.
        let request = self.requests[self.first..]
            .iter()
            .position(|r| r.slot == slot)
            .map(|i| self.first + i)
            .unwrap_or_else(|| {
                let index = self.requests.len();
                self.requests.push(Request {
                    position: self.current,
                    state: self.state,
                    slot,
                });
                index
            });
        if let Some(last) = self.patches.last_mut()
            && last.request == request
            && last.layer == layer
            && last.end == start
        {
            last.end = end;
        } else {
            self.patches.push(Patch {
                request,
                layer,
                start,
                end,
            });
        }
    }
    pub fn apply(&self, colors: &[u32], layers: &mut [Vec<Triangle>; 3]) {
        for patch in &self.patches {
            let argb = colors[patch.request];
            let color = [16, 8, 0, 24].map(|shift| ((argb >> shift) & 255) as f32 / 255.);
            for triangle in &mut layers[patch.layer][patch.start..patch.end] {
                triangle.colors = [color; 3];
            }
        }
    }
}
