#pragma once
#include <luisa/std.hpp>
#include <bsdfs/base/fresnel.hpp>
#include <bsdfs/base/microfacet.hpp>
#include <bsdfs/base/bsdf.hpp>
#include <bsdfs/base/utils.hpp>
#include <spectrum/spectrum.hpp>
#include <bsdfs/base/refractive_fresnel_microfacet.hpp>
#include <bsdfs/base/reflective_fresnel_microfacet.hpp>

namespace mtl {

using namespace luisa::shader;

class DielectricSpecularBRDF {
	float3 multiple_scattering;
	using FresnelMicrofacet = ReflectiveFresnelMicrofacet;

public:
	static constexpr BSDFFlags const flags = BSDFFlags(BSDFFlags::SpecularReflection | BSDFFlags::DeltaReflection);

	DielectricSpecularBRDF() = default;

	void init(float3 wi, auto const& bp, auto const& p, auto& data) {
		multiple_scattering = data.specular_microfacet.dielectric_ms_compensation(
			wi.z, data.specular_fresnel.energy_ior());
	}

	Throughput eval(float3 wi, float3 wo, auto& data) const {
		Throughput result = FresnelMicrofacet::eval(wi, wo, data, data.specular_microfacet, data.specular_fresnel);
		if (result && is_non_delta(result.flags)) {
			// Branch-separated R/T multiple-scattering scale.
			result.val *= multiple_scattering.x;
		}
		return result;
	}
	BSDFSample sample(float3 wi, auto& data, auto& volume_stack) const {
		BSDFSample result = FresnelMicrofacet::sample(wi, data, data.specular_microfacet, data.specular_fresnel);
		if (result && is_non_delta(result.throughput.flags)) {
			// Branch-separated R/T multiple-scattering scale.
			result.throughput.val *= multiple_scattering.x;
		}
		return result;
	}
	float pdf(float3 wi, float3 wo, auto& data) const {
		return FresnelMicrofacet::pdf(wi, wo, data, data.specular_microfacet, data.specular_fresnel);
	}
	float3 tint_out(float3 wo, auto& data) const {
		return 1.0f;
	}
	float3 trans(float3 wi, auto& data, float3 base_energy) const {
		if (wi.z < 0) return 1.0f;
		return 1.0f - multiple_scattering.z;
	}
	float3 energy(float3 wi, auto& data) const {
		if (wi.z < 0) return 0.0f;
		if (!data.specular_microfacet.effectivelySmooth() && data.specular_fresnel.ior() != 1.0f) {
			if (!is_specular(data.sampling_flags))
				return 0.0f;
		} else {
			if (!is_delta(data.sampling_flags))
				return 0.0f;
		}
		if (!is_reflective(data.sampling_flags)) {
			return 0.0f;
		}
		return multiple_scattering.z * data.specular_fresnel.specular_color.color(data.spectrumed);
	}
};

}// namespace mtl
