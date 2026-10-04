/***************************************************************************
 # Copyright (c) 2015-26, NVIDIA CORPORATION. All rights reserved.
 #
 # Redistribution and use in source and binary forms, with or without
 # modification, are permitted provided that the following conditions
 # are met:
 #  * Redistributions of source code must retain the above copyright
 #    notice, this list of conditions and the following disclaimer.
 #  * Redistributions in binary form must reproduce the above copyright
 #    notice, this list of conditions and the following disclaimer in the
 #    documentation and/or other materials provided with the distribution.
 #  * Neither the name of NVIDIA CORPORATION nor the names of its
 #    contributors may be used to endorse or promote products derived
 #    from this software without specific prior written permission.
 #
 # THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS "AS IS" AND ANY
 # EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 # IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 # PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 # CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 # EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 # PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 # PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 # OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 # (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 # OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **************************************************************************/

#language slang 2026
module "restir/paired_neighbors.slang";

import "restir/rng.slang";

public static const uint PAIRED_NEIGHBOR_EXTENT = 256u;
public static const uint PAIRED_NEIGHBOR_COUNT = 3u;
public static const uint PAIRED_NEIGHBOR_SIZES[3] = {254u, 232u, 196u};

// Same symmetry transform and random-bit partition as Falcor PairedNeighborTransform.slang.
// The table is caller-owned; u16 source assets are expanded once to uint for GPU consumption.
public struct PairedNeighborTransform {
    public int offsetX;
    public int offsetY;
    public int imageWidth;
    public int imageHeight;
    public bool flipX;
    public bool flipY;
    public bool transpose;

    public __init(inout SampleGenerator sg, int imageWidth, int imageHeight) {
        uint r = sg.next();
        this.imageHeight = imageHeight;
        this.imageWidth = imageWidth;
        offsetX = (r & 0x7fffu) % imageWidth;
        r >>= 15;
        offsetY = (r & 0x3fffu) % imageHeight;
        r >>= 14;
        flipX = bool(r & 1);
        r >>= 1;
        flipY = bool(r & 1);
        r >>= 1;
        transpose = bool(r & 1);
    }
    public void transformCoordinates(int neighborIndex, int x, int y, int imageWidth,
                                     int imageHeight, inout int out_x, inout int out_y) {
        const int textureSize = int(PAIRED_NEIGHBOR_SIZES[neighborIndex]);
        x = (x + offsetX) % textureSize;
        y = (y + offsetY) % textureSize;
        if (flipX)
            x = textureSize - 1 - x;
        if (flipY)
            y = textureSize - 1 - y;
        if (transpose) {
            int temp = x;
            x = y;
            y = temp;
        }
        out_x = x;
        out_y = y;
    }
    public void transformDelta(int dx, int dy, inout int out_dx, inout int out_dy) {
        if (transpose) {
            int temp = dx;
            dx = dy;
            dy = temp;
        }
        if (flipX)
            dx = -dx;
        if (flipY)
            dy = -dy;
        out_dx = dx;
        out_dy = dy;
    }
    public void unpackDelta(uint packed, inout int dx, inout int dy) {
        dx = int(packed & 0xFF) - 128;
        dy = int(packed >> 8) - 128;
    }
    public uint readReuseTexture(StructuredBuffer<uint> pairingDeltaTextures, int index, int x,
                                 int y) {
        int textureSize = int(PAIRED_NEIGHBOR_SIZES[index]);
        return pairingDeltaTextures[256 * 256 * index + y * textureSize + x];
    }
    public void readTransformedDelta(StructuredBuffer<uint> pairingDeltaTextures, int neighborIndex,
                                     int x, int y, inout int out_dx, inout int out_dy) {
        int tx = 0, ty = 0, dx = 0, dy = 0;
        transformCoordinates(neighborIndex, x, y, imageWidth, imageHeight, tx, ty);
        unpackDelta(readReuseTexture(pairingDeltaTextures, neighborIndex, tx, ty), dx, dy);
        transformDelta(dx, dy, out_dx, out_dy);
    }
    public uint readReuseTexture(uint *pairingDeltaTextures, int index, int x, int y) {
        int textureSize = int(PAIRED_NEIGHBOR_SIZES[index]);
        return pairingDeltaTextures[256 * 256 * index + y * textureSize + x];
    }
    public void readTransformedDelta(uint *pairingDeltaTextures, int neighborIndex, int x, int y,
                                     inout int out_dx, inout int out_dy) {
        int tx = 0, ty = 0, dx = 0, dy = 0;
        transformCoordinates(neighborIndex, x, y, imageWidth, imageHeight, tx, ty);
        unpackDelta(readReuseTexture(pairingDeltaTextures, neighborIndex, tx, ty), dx, dy);
        transformDelta(dx, dy, out_dx, out_dy);
    }
    public int applyDeltaPeriodic(int x, int dx, int width) {
        int r = (x + dx) % width;
        if (r < 0)
            r += width;
        return r;
    }
};
