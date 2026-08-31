#language slang 2026
module "service/reconstruct/control.slang";

public static const uint PRIME_RECONSTRUCTION_MATERIAL_CLASS_MASK = 0x03u;
public static const uint PRIME_RECONSTRUCTION_SURFACE_VALID = 0x04u;
public static const uint PRIME_RECONSTRUCTION_HISTORY_VALID = 0x08u;
public static const uint PRIME_RECONSTRUCTION_MOTION_VALID = 0x10u;
public static const uint PRIME_RECONSTRUCTION_CONTROL_VALID_MASK = 0x1fu;

public uint primePackReconstructionControl(
        uint materialClass,
        bool surfaceValid,
        bool historyValid) {
    return (materialClass & PRIME_RECONSTRUCTION_MATERIAL_CLASS_MASK)
            | (surfaceValid ? PRIME_RECONSTRUCTION_SURFACE_VALID : 0u)
            | (historyValid ? PRIME_RECONSTRUCTION_HISTORY_VALID : 0u);
}

public uint primeSetReconstructionMotionValid(uint control, bool valid) {
    return valid
            ? control | PRIME_RECONSTRUCTION_MOTION_VALID
            : control & ~PRIME_RECONSTRUCTION_MOTION_VALID;
}

public uint primeReconstructionMaterialClass(uint control) {
    return control & PRIME_RECONSTRUCTION_MATERIAL_CLASS_MASK;
}

public bool primeReconstructionSurfaceValid(uint control) {
    return (control & PRIME_RECONSTRUCTION_SURFACE_VALID) != 0u;
}

public bool primeReconstructionHistoryValid(uint control) {
    return (control & PRIME_RECONSTRUCTION_HISTORY_VALID) != 0u;
}

public bool primeReconstructionMotionValid(uint control) {
    return (control & PRIME_RECONSTRUCTION_MOTION_VALID) != 0u;
}
