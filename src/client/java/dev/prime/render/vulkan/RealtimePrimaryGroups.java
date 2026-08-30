package dev.prime.render.vulkan;

/** Group order for the visible-primary realtime prefix. */
final class RealtimePrimaryGroups {
    static final int CAMERA_TRACE = 0;
    static final int VISIBLE_DIRECT = 1;
    static final int SURFACE_SPLIT = 2;
    static final int DELTA_WALK_0 = 3;
    static final int GUIDE_DELTA_WALK_0 = 4;
    static final int DELTA_WALK_1 = 5;
    static final int GUIDE_DELTA_WALK_1 = 6;
    static final int LANDING_LIGHT_SELECT = 7;
    static final int LANDING_DIRECT = 8;
    static final int LANDING_SCATTER = 9;

    private RealtimePrimaryGroups() {}
}
