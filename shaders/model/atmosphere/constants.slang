// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "model/atmosphere/constants.slang";
import "prime_abi_types.slang";
public static const float ATM_PI = 3.141592653589793;
public static const float ATM_INV_PI = 0.3183098861837907;
public static const float ATM_INV_4PI = 0.07957747154594767;
public static const float ATM_RAYLEIGH_PHASE_SCALE = 0.05968310365946075;
// World positions and travelled distances enter this unchanged physical atmosphere
// through ATM_WORLD_UNIT_SCALE_KM. The explicit factor documents that coordinate contract.
public static const float ATM_WORLD_TO_ATMOSPHERE_SCALE = PRIME_ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE;
public static const float ATM_BOTTOM_RADIUS_KM = PRIME_ATMOSPHERE_BOTTOM_RADIUS_KM;
public static const float ATM_TOP_RADIUS_KM = PRIME_ATMOSPHERE_TOP_RADIUS_KM;
public static const float ATM_THICKNESS_KM = ATM_TOP_RADIUS_KM - ATM_BOTTOM_RADIUS_KM;
public static const float ATM_WORLD_GROUND_Y = PRIME_ATMOSPHERE_WORLD_GROUND_Y;
public static const float ATM_WORLD_UNIT_SCALE_KM = PRIME_ATMOSPHERE_WORLD_UNIT_SCALE_KM;
public static const float ATM_AERIAL_MAX_DISTANCE_KM = PRIME_ATMOSPHERE_AERIAL_MAX_DISTANCE_KM;
public static const float ATM_SPACE_SUN_INTENSITY = PRIME_ATMOSPHERE_SPACE_SUN_INTENSITY;
public static const float ATM_SUN_ANGULAR_RADIUS_RADIANS = PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS;
