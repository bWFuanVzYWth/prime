#pragma once
#include <luisa/std.hpp>
#include <bsdfs/base/fresnel.hpp>
#include <bsdfs/base/microfacet.hpp>
#include <bsdfs/base/bsdf.hpp>
#include <bsdfs/base/utils.hpp>
#include <spectrum/spectrum.hpp>
#include <bsdfs/base/reflective_fresnel_microfacet.hpp>

namespace mtl {

using namespace luisa::shader;

class DielectricCoatBRDF {
	GGXMicrofacet microfacet;
	DielectricFresnel fresnel;
	spectrum::SpectrumColor tint_color;
	float darkening;
	float base_roughness;
	float3 multiple_scattering;

public:
	static constexpr BSDFFlags const flags = BSDFFlags::SpecularReflection | BSDFFlags::DeltaReflection;

	DielectricCoatBRDF() = default;
	void init(float3 wi, auto const& bp, auto const& p, auto& data) {
		microfacet.init(
			specular_ndf_roughnesses(p.coat.roughness,
									 p.coat.roughness_anisotropy));
		fresnel.init(p.coat.ior * data.inv_out_ior);
		tint_color = spectrum::SpectrumColor(sqrt(p.coat.color.origin()), sqrt(p.coat.color.spectral()));
		darkening = p.coat.darkening;
		base_roughness = bp.specular.roughness;
		auto dielectric_ior = data.specular_fresnel.energy_ior();
		auto dielectric_roughness = lerp(1.0f, base_roughness, ior_to_f0(dielectric_ior));
		base_roughness = lerp(dielectric_roughness, base_roughness, bp.weight.metalness);
		auto coat_wi = data.coat_local_onb.to_local(wi);
		multiple_scattering = microfacet.dielectric_ms_compensation(
			coat_wi.z, fresnel.ior);
	}

	Throughput eval(float3 wi, float3 wo, auto& data) const {
		wi = data.coat_local_onb.to_local(wi);
		wo = data.coat_local_onb.to_local(wo);
		wo.z = abs(wo.z);

		auto result = ReflectiveFresnelMicrofacet::eval(wi, wo, data, microfacet, fresnel);
		if (result && is_non_delta(result.flags)) {
			// Branch-separated R/T multiple-scattering scale.
			result.val *= multiple_scattering.x;
		}
		return result;
	}
	BSDFSample sample(float3 wi, auto& data, auto& volume_stack) const {
		wi = data.coat_local_onb.to_local(wi);
		auto result = ReflectiveFresnelMicrofacet::sample(wi, data, microfacet, fresnel);
		if (result) {
			if (is_non_delta(result.throughput.flags)) {
				// Branch-separated R/T multiple-scattering scale.
				result.throughput.val *= multiple_scattering.x;
			}
			result.wo = data.coat_local_onb.to_world(result.wo);
		}
		return result;
	}
	float pdf(float3 wi, float3 wo, auto& data) const {
		wi = data.coat_local_onb.to_local(wi);
		wo = data.coat_local_onb.to_local(wo);
		wo.z = abs(wo.z);

		return ReflectiveFresnelMicrofacet::pdf(wi, wo, data, microfacet, fresnel);
	}
	float3 tint_out(float3 wo, auto& data) const {
		wo = data.coat_local_onb.to_local(wo);
		return coat_view_dependent_absorption(tint_color.color(data.spectrumed),
											  abs(wo.z), fresnel.ior, -1.0f);
	}
	float3 trans(float3 wi, auto& data, float3 base_energy) const {
		wi = data.coat_local_onb.to_local(wi);
		auto view_tint = coat_view_dependent_absorption(tint_color.color(data.spectrumed),
														abs(wi.z), fresnel.ior, 1.0f);
		if (wi.z < 0.0f) return view_tint;

		float Kr = 1.0f - (1.0f - fresnel.energy()) / sqr(fresnel.ior);
		float Ks = fresnel.unpolarized(wi.z);
		float K = lerp(Ks, Kr, base_roughness);

		auto tint_in = lerp(float3(1.0f),
							ite(base_energy == 1.0f,
								float3(1.0f),
								(1.0f - K) / (1.0f - base_energy * K)),
							float3(darkening)) *
					   view_tint;

		return (1.0f - multiple_scattering.z) * tint_in;
	}
	float3 energy(float3 wi, auto& data) const {
		wi = data.coat_local_onb.to_local(wi);
		if (wi.z < 0.0f)
			return 0.0f;
		if (microfacet.effectivelySmooth()) {
			if (!(data.sampling_flags & BSDFFlags::DeltaReflection))
				return 0.0f;
		} else {
			if (!(data.sampling_flags & BSDFFlags::SpecularReflection))
				return 0.0f;
		}
		return multiple_scattering.z;
	}
};

}// namespace mtl
