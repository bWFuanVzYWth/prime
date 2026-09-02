package dev.prime.render.terrain;

import org.jspecify.annotations.Nullable;

/** Immutable material facts addressed by one renderer-lifetime MaterialId. */
public record MaterialKey(int textureId, @Nullable MediumKey medium, int materialControl) {
    public MaterialKey {
        if (textureId <= 0 || textureId > MaterialIdResolver.MAX_ID) {
            throw new IllegalArgumentException(
                    "Material TextureId exceeds its exact u16 domain");
        }
        PrimitivePacking.requireValidControl(materialControl);
        if ((materialControl & (PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY)) != 0) {
            throw new IllegalArgumentException(
                    "Material key contains geometry-varying orientation controls");
        }
        boolean solidMedium = PrimitivePacking.isTransmissive(materialControl)
                && !PrimitivePacking.isThinWalled(materialControl);
        if (solidMedium != (medium != null)) {
            throw new IllegalArgumentException(
                    "Material medium disagrees with transmissive topology");
        }
    }
}
