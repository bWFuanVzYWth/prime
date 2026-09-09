// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/shader_record.slang";

public struct PrimeWavefrontShaderRecord {
    public uint control;
};

[[vk::shader_record]]
public ConstantBuffer<PrimeWavefrontShaderRecord> primeWavefrontShaderRecord;
