/***************************************************************************
 # Copyright (c) 2015-23, NVIDIA CORPORATION. All rights reserved.
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
module "restir/rng.slang";

// Falcor TinyUniformSampleGenerator: unchanged 32-bit LCG, Morton seed, TEA and float conversion.
public uint2 blockCipherTEA(uint v0, uint v1, uint iterations = 16) {
    uint sum = 0;
    const uint delta = 0x9e3779b9;
    const uint k[4] = {0xa341316c, 0xc8013ea4, 0xad90777d, 0x7e95761e};
    for (uint i = 0; i < iterations; i++) {
        sum += delta;
        v0 += ((v1 << 4) + k[0]) ^ (v1 + sum) ^ ((v1 >> 5) + k[1]);
        v1 += ((v0 << 4) + k[2]) ^ (v0 + sum) ^ ((v0 >> 5) + k[3]);
    }
    return uint2(v0, v1);
}
public uint interleave_32bit(uint2 v) {
    uint x = v.x & 0x0000ffff;
    x = (x | (x << 8)) & 0x00FF00FF;
    x = (x | (x << 4)) & 0x0F0F0F0F;
    x = (x | (x << 2)) & 0x33333333;
    x = (x | (x << 1)) & 0x55555555;
    uint y = v.y & 0x0000ffff;
    y = (y | (y << 8)) & 0x00FF00FF;
    y = (y | (y << 4)) & 0x0F0F0F0F;
    y = (y | (y << 2)) & 0x33333333;
    y = (y | (y << 1)) & 0x55555555;
    return x | (y << 1);
}
public struct TinyUniformSampleGenerator {
    public uint rng = 0;
    public __init() {}
    public __init(uint seed) {
        rng = seed;
    }
    public __init(uint2 pixel, uint sampleNumber) {
        rng = blockCipherTEA(interleave_32bit(pixel), sampleNumber).x;
    }
    public[mutating] float next1D() {
        return (next() >> 8) * 0x1p-24;
    }
    public[mutating] float2 next2D() {
        float2 sample;
        sample.x = next1D();
        sample.y = next1D();
        return sample;
    }
    public[mutating] float3 next3D() {
        float3 sample;
        sample.x = next1D();
        sample.y = next1D();
        sample.z = next1D();
        return sample;
    }
    public[mutating] float4 next4D() {
        float4 sample;
        sample.x = next1D();
        sample.y = next1D();
        sample.z = next1D();
        sample.w = next1D();
        return sample;
    }
    public[mutating] uint next() {
        rng = 1664525u * rng + 1013904223u;
        return rng;
    }
};
public typedef TinyUniformSampleGenerator SampleGenerator;
public float sampleNext1D(inout SampleGenerator sg) {
    return (sg.next() >> 8) * 0x1p-24;
}
public float2 sampleNext2D(inout SampleGenerator sg) {
    float2 sample;
    sample.x = sampleNext1D(sg);
    sample.y = sampleNext1D(sg);
    return sample;
}
public float3 sampleNext3D(inout SampleGenerator sg) {
    float3 sample;
    sample.x = sampleNext1D(sg);
    sample.y = sampleNext1D(sg);
    sample.z = sampleNext1D(sg);
    return sample;
}
public float4 sampleNext4D(inout SampleGenerator sg) {
    float4 sample;
    sample.x = sampleNext1D(sg);
    sample.y = sampleNext1D(sg);
    sample.z = sampleNext1D(sg);
    sample.w = sampleNext1D(sg);
    return sample;
}
