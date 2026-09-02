#language slang 2026
module "math/basis.slang";

public void primeBasis(float3 normal, out float3 tangent, out float3 bitangent)
{
    float3 helper = abs(normal.z) < 0.999
            ? float3(0.0, 0.0, 1.0)
            : float3(1.0, 0.0, 0.0);
    tangent = normalize(cross(helper, normal));
    bitangent = cross(normal, tangent);
}
public float3 primeLocalToWorld(float3 localDirection, float3 normal)
{
    float3 tangent;
    float3 bitangent;
    primeBasis(normal, tangent, bitangent);
    return normalize(
            tangent * localDirection.x
                    + bitangent * localDirection.y
                    + normal * localDirection.z);
}
