// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/image/access.slang";

public int2 primeReadonlyImageSize(readonly RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int2 primeReadonlyImageSize(readonly RWTexture2D<float2> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int3 primeReadonlyImageSize(readonly RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

public int2 primeWriteImageSize(writeonly RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int3 primeWriteImageSize(writeonly RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

public float4 primeReadonlyImageLoad(
        readonly RWTexture2D<float4> source, int2 coordinate) {
    return source[coordinate];
}

public float2 primeReadonlyImageLoad(
        readonly RWTexture2D<float2> source, int2 coordinate) {
    return source[coordinate];
}

public void primeWriteImageStore(
        writeonly RWTexture2D<float4> target, int2 coordinate, float4 value) {
    target[coordinate] = value;
}

public void primeWriteImageStore(
        writeonly RWTexture3D<float4> target, int3 coordinate, float4 value) {
    target[coordinate] = value;
}

public int2 primeImageSize(RWTexture2D<float4> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int2 primeImageSize(RWTexture2D<float2> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int2 primeImageSize(RWTexture2D<float> source) {
    uint width;
    uint height;
    source.GetDimensions(width, height);
    return int2(width, height);
}

public int3 primeImageSize(RWTexture3D<float4> source) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    return int3(width, height, depth);
}

public float4 primeImageLoad(RWTexture2D<float4> source, int2 coordinate) {
    return source[coordinate];
}

public float2 primeImageLoad(RWTexture2D<float2> source, int2 coordinate) {
    return source[coordinate];
}

public float primeImageLoad(RWTexture2D<float> source, int2 coordinate) {
    return source[coordinate];
}

public float4 primeImageLoad(RWTexture3D<float4> source, int3 coordinate) {
    return source[coordinate];
}

public void primeImageStore(
        RWTexture2D<float4> target, int2 coordinate, float4 value) {
    target[coordinate] = value;
}

public void primeImageStore(
        RWTexture2D<float2> target, int2 coordinate, float2 value) {
    target[coordinate] = value;
}

public void primeImageStore(
        RWTexture2D<float> target, int2 coordinate, float value) {
    target[coordinate] = value;
}

public void primeImageStore(
        RWTexture3D<float4> target, int3 coordinate, float4 value) {
    target[coordinate] = value;
}
