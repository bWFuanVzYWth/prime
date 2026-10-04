#language slang 2026
module restir_bda_layout_test;
import "restir/reservoir.slang";

struct Push {
    PathReservoir *reservoir;
    ReconnectionData *reconnection;
    PrecomputedShiftData *shift;
};
[[vk::push_constant]] Push pc;

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    PathReservoir reservoir = {};
    reservoir.M = 1.f;
    pc.reservoir[id.x] = reservoir;
    ReconnectionData reconnection = {};
    reconnection.rcFootprintFactor = 1.f;
    pc.reconnection[id.x] = reconnection;
    PrecomputedShiftData shift = {};
    shift.Jacobian = 1.f;
    pc.shift[id.x] = shift;
}
