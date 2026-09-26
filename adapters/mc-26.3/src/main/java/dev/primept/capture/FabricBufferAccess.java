package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Accesses Indigo's actual per-submit binding cache; never repeats a material callback. */
public interface FabricBufferAccess {
    VertexConsumer primept$buffer(ChunkSectionLayer layer);
}
