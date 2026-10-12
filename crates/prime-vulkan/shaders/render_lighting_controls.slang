#language slang 2026
module render_lighting_controls;

// 32-byte frame-slot uniform at set 1, binding 6. It does not extend PT push or scratch.
public struct RenderLightingControls {
    public float4 stars;    // observer latitude, season radians, absolute star scale, reserved
    public float4 emission; // host/texture power mix, block/material 2^EV, reserved, reserved
};
