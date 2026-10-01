#pragma once
#include <luisa/std.hpp>
#include <bsdfs/base/utils.hpp>
#include <luisa/resources/common_extern.hpp>

namespace mtl {

using namespace luisa::shader;

class GGXMicrofacet {
public:
	float2 alpha;

	constexpr GGXMicrofacet() = default;

	void init(float2 alpha) {
		this->alpha = alpha;
	}

	bool effectivelySmooth() const { return reduce_max(alpha) < 1e-4f; }

	float lambda(float3 w) const {
		return (sqrt(1.0f + reduce_sum(sqr(alpha * w.xy)) / sqr(w.z)) - sign(w.z)) * 0.5f;
	}
	float D(float3 h) const {
		float result = rcp(pi * reduce_product(alpha) * sqr(reduce_sum(sqr(h.xy / alpha)) + sqr(h.z)));
		// Prevent potential numerical issues in other stages of the model
		return result * h.z > 1e-20f ? result : 0.f;
	}
	float G1(float3 w) const {
		return 1.0f / lambda(-w);
	}
	float G2r(float3 wi, float3 wo) const {
		if (wi.z < 0.0f) {
			wi = -wi;
			wo = -wo;
		}
		return 1.0f / (lambda(-wi) + lambda(wo));
	}
	float G2t(float3 wi, float3 wo) const {
		if (wi.z < 0.0f) {
			wi = -wi;
			wo = -wo;
		}
		return beta(lambda(-wi), lambda(wo));
	}
	float3 sample(float3 wi, float2 rand, bool reflect_only = false) const {

		float3 vh = normalize(float3(alpha * wi.xy, wi.z));

		// "Sampling Visible GGX Normals with Spherical Caps"
		// Jonathan Dupuy & Anis Benyoub - High Performance Graphics 2023
		float phi = (2 * pi) * rand.x;
		float k = 1.0f;
		// If we know we will be reflecting the view vector around the sampled micronormal, we can
		// tweak the range a bit more to eliminate some of the vectors that will point below the horizon
		// "Bounded VNDF Sampling for the Smith–GGX BRDF"
		// Yusuke Tokuyoshi & Kenta Eto - Proc. ACM Comput. Graph. Interact. Tech. 2024
		if (reflect_only) {
			float a = saturate(min(alpha.x, alpha.y));
			float s = 1.0f + length(wi.xy);
			float a2 = a * a, s2 = s * s;
			k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
		}

		float Z = lerp(1.0f, -k * vh.z, rand.y);
		float sin_h = sqrt(saturate(1 - Z * Z));
		float X = sin_h * cos(phi);
		float Y = sin_h * sin(phi);
		float3 h = float3(X, Y, Z) + vh;

		// unstretch
		return normalize(float3(alpha * h.xy, max(0.0f, h.z)));
	}

	float pdf(float3 wi, float3 h, bool reflect_only = false) const {
		float LenV = length(float3(wi.x * alpha.x, wi.y * alpha.y, wi.z));
		float k = 1.0f;
		if (reflect_only) {
			float a = saturate(min(alpha.x, alpha.y));
			float s = 1.0f + length(wi.xy);
			float ka2 = a * a, s2 = s * s;
			k = (s2 - ka2 * s2) / (s2 + ka2 * wi.z * wi.z);// Eq. 5
		}
		return (2.0f * D(h) * dot(wi, h)) / (k * wi.z + LenV);
	}

	float alpha2() const {
		return 0.5 * reduce_sum(sqr(alpha));
	}

	float3 dir_albedo(float cos_theta, float3 f0, float3 f90) const {
		// Rational quadratic fit to Monte Carlo data for reflective GGX directional albedo.
		float x = cos_theta;
		float y2 = alpha2();
		float x2 = sqr(x);
		float y = sqrt(y2);
		float4 r = float4(0.1003, 0.9345, 1.0, 1.0) +
				   float4(-0.6303, -2.323, -1.765, 0.2281) * x +
				   float4(9.748, 2.229, 8.263, 15.94) * y +
				   float4(-2.038, -3.748, 11.53, -55.83) * x * y +
				   float4(29.34, 1.424, 28.96, 13.08) * x2 +
				   float4(-8.245, -0.7684, -7.507, 41.26) * y2 +
				   float4(-26.44, 1.436, -36.11, 54.9) * x2 * y +
				   float4(19.99, 0.2913, 15.86, 300.2) * x * y2 +
				   float4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
		float2 AB = saturate(r.xy / r.zw);
		return f0 * AB.x + f90 * AB.y;
	}

	float3 dielectric_ms_compensation(float cos_theta, float ior) const {
		if (ior == 1.0f) return float3(1.0f, 1.0f, 0.0f);

		constexpr float q_min = 5.960464477539063e-8f;
		constexpr float q_critical = 0.00010002000500139488f;
		constexpr float q_max = 0.999499874937461f;
		constexpr float near_log2_scale = 0.09334822745469708f;
		constexpr float far_log2_min = -13.287279491668832f;
		constexpr float far_log2_scale = 0.04123374454764629f;

		bool inside = (cos_theta < 0.0f) != (ior < 1.0f);
		float mu = clamp(abs(cos_theta), 0.02f, 1.0f);
		float q = clamp(abs((ior - 1.0f) / (ior + 1.0f)), q_min, q_max);
		float critical = min(2.0f * sqrt(q) / (1.0f + q), 0.9999999403953552f);
		bool near_inside = inside && q <= q_critical;
		bool below_critical = inside && !near_inside && mu < critical;

		float near_u = saturate((log2(q) + 24.0f) * near_log2_scale);
		float near_z = near_u - 0.65f * near_u * (1.0f - near_u) * (2.0f * near_u - 1.0f);
		float far_u = saturate((log2(q / (1.0f - q)) - far_log2_min) * far_log2_scale);
		float far_z = sqrt(far_u);
		float exterior_z = ite(q <= q_critical,
			(12.0f / 47.0f) * near_z,
			(12.0f + 35.0f * far_z) / 47.0f);
		float logical_z = ite(inside, ite(near_inside, near_z, far_z), exterior_z);
		float z_offset = ite(inside, ite(near_inside, 48.0f, ite(below_critical, 111.0f, 63.0f)), 0.0f);
		float z_extent = ite(near_inside, 15.0f, 47.0f);

		float roughness = sqrt(sqrt(alpha2()));
		float roughness_coordinate = sqrt(saturate((roughness - 0.01f) / 0.99f));
		float linear_view = (mu - 0.02f) / 0.98f;
		float below_view = (mu - 0.02f) / max(critical - 0.02f, 1.0e-20f);
		float above_view = (mu - critical) / max(1.0f - critical, 1.0e-20f);
		float view = saturate(ite(below_critical, below_view,
			ite(inside && !near_inside, above_view, linear_view)));
		view = ite(view <= 0.5f,
			0.5f * sqrt(2.0f * view),
			1.0f - 0.5f * sqrt(2.0f * (1.0f - view)));
		view -= 0.64f * view * (1.0f - view) * (2.0f * view - 1.0f);

		float3 uvw = float3(
			(view * 43.0f + 0.5f) / 44.0f,
			(roughness_coordinate * 31.0f + 0.5f) / 32.0f,
			(z_offset + logical_z * z_extent + 0.5f) / 159.0f);
		float4 encoded = g_volume_heap.uniform_idx_volume_sample(
			heap_indices::transmission_ggx_energy_idx, uvw,
			Filter::LINEAR_POINT, Address::EDGE);

		float a = 0.5f * (sqrt(1.0f + alpha2() * (1.0f - mu * mu) / (mu * mu)) + 1.0f);
		float inverse_a = rcp(a);
		float eta_one_log2_t = 1.0f - 2.0f * a + 0.5f * log2(pi * a) - 1.45676773e-5f +
			inverse_a * (1.80676418e-1f + inverse_a * (-1.85532904e-3f - 4.61868116e-3f * inverse_a));
		float eta_q0 = 3.0e-4f + 6.82190321e-2f * roughness * mu;
		float eta_factor = eta_q0 / (q + eta_q0) * eta_one_log2_t;
		float q_fraction = fract(logical_z * z_extent);
		float transmission_correction = (2.0f / 3.0f) * encoded.w *
			q_fraction * (1.0f - q_fraction);
		float2 scale = exp2(clamp(
			float2(encoded.x, encoded.y - eta_factor + transmission_correction),
			-80.0f, 80.0f));
		float encoded_b = saturate(encoded.z);
		float b_endpoint = 1.0f - abs(2.0f * encoded_b - 1.0f);
		float b_edge = 0.5f * sqr(b_endpoint) * sqrt(b_endpoint);
		float helper_r = ite(encoded_b <= 0.5f, b_edge, 1.0f - b_edge);
		return float3(scale, helper_r);
	}

	float dir_albedo(float cos_theta) const {
		return dir_albedo(cos_theta, float3{1.0f}, float3{1.0f}).x;
	}
};

}// namespace mtl
