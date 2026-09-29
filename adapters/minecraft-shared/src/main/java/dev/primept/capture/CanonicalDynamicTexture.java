package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;

/** Borrowed host image metadata; this interface owns neither pixels nor a renderer resource. */
public interface CanonicalDynamicTexture {
    NativeImage primept$peekPixels();
    boolean primept$hasUploadedPixels();
    boolean primept$pixelsExposed();
}
