// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/trace.slang";

// Traversal consumes exact source identity; hit/miss shading owns a separate output payload.
public struct PrimeTraceRequest {
    public uint2 source;
    // Slang requires an aggregate write before reading an incoming any-hit payload.
    public uint reserved;
};
