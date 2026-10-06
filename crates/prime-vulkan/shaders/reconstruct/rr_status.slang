#language slang 2026
module "reconstruct/rr_status.slang";

// R8_UNORM storage contains a small integer / 255. It is private to K1/post/display,
// never an SDK validity mask. Explicit reflection motion may coexist with failed guides.
public static const uint PRIME_RR_PRIMARY_PENDING = 1u;
public static const uint PRIME_RR_REFLECTION_PENDING = 2u;
public static const uint PRIME_RR_EXPLICIT_REFLECTION = 4u;
public static const uint PRIME_RR_FOREGROUND = 8u; // Actual first query visibility, before PSR.
// Conservative per-frame approximation hints shared by main/reflection guides. A later
// terminal may replace some components without clearing hints belonging to the other guide.
public static const uint PRIME_RR_SURFACE_PROXY = 16u;
public static const uint PRIME_RR_MOTION_PROXY = 32u;
public static const uint PRIME_RR_BUDGET_PROXY = 64u;
public static const uint PRIME_RR_SURFACE_CANDIDATE = 128u;
public bool primeRrForeground(float packed) {
    return (uint(round(saturate(packed) * 255.0)) & PRIME_RR_FOREGROUND) != 0;
}
public uint primeRrGuideStatus(float packed) {
    return uint(round(saturate(packed) * 255.0));
}
public bool primeRrGuideComplete(float packed) {
    return (primeRrGuideStatus(packed) &
            (PRIME_RR_PRIMARY_PENDING | PRIME_RR_REFLECTION_PENDING)) == 0;
}
