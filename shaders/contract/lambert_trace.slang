// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/lambert_trace.slang";

// Traversal keeps exact source identity separate from the 96-byte shading output.
public struct PrimeLambertTracePayload {
    public uint2 source;
    // Slang requires an aggregate write before reading the incoming any-hit payload.
    public uint reserved;
};
