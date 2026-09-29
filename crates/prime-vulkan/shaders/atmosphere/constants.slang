// Ported from Prime; GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
#language slang 2026
module "atmosphere/constants.slang";

public static const float ATM_PI = 3.141592653589793;
public static const float ATM_RAYLEIGH_PHASE_SCALE = 0.05968310365946075;
public static const float ATM_BOTTOM_RADIUS_KM = 6360.0;
public static const float ATM_TOP_RADIUS_KM = 6480.0;
public static const float ATM_THICKNESS_KM = ATM_TOP_RADIUS_KM - ATM_BOTTOM_RADIUS_KM;
// World positions and travelled distances enter the physical atmosphere in kilometres.
public static const float ATM_WORLD_UNIT_SCALE_KM = 0.001;
public static const float ATM_AERIAL_MAX_DISTANCE_KM = 2.048;
public static const float ATM_SPACE_SUN_INTENSITY = 12.5;
public static const float ATM_SUN_ANGULAR_RADIUS_RADIANS = 0.00471;
