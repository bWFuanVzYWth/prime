#language slang 2026
module "light_sampling.slang";

public struct LightNode {
    public float3 lower;
    public float power;
    public float3 upper;
    public uint child;
};

// Power proposal shared by production and the opt-in sampling experiment. Bounds do not
// affect this PDF. Residual uniform mass passes from a world leaf to its local tree.
public uint selectPowerLight(LightNode *nodes, inout float sample) {
    LightNode node = nodes[0];
    float weight = sample * node.power;
    while ((node.child & 0x80000000u) == 0) {
        LightNode left = nodes[node.child];
        if (weight < left.power)
            node = left;
        else {
            weight -= left.power;
            node = nodes[node.child + 1];
        }
    }
    sample = min(weight / node.power, asfloat(0x3f7fffffu));
    return node.child & 0x7fffffffu;
}
