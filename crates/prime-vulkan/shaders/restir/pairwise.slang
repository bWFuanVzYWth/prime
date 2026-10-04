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
module "restir/pairwise.slang";

import "restir/reservoir.slang";

public struct PairwiseFactor {
    public float c_sum;
    public float c_c;
};

// Pure formulas from Falcor Shift.slang and Spatial/TemporalResampling.cs.slang.
// Inputs use the mixed PSS-solid-angle measure. The caller validates sample support.
public bool isJacobianInvalid(float Jacobian) {
    return Jacobian <= 0.f || isnan(Jacobian) || isinf(Jacobian);
}
public bool isIntegrandInvalid(float3 Integrand) {
    return all(Integrand == 0.f) || any(isnan(Integrand) || isinf(Integrand) || Integrand < 0.f);
}
public float reconnectionGeometryTerm(float rcCosine, float distanceSquared) {
    return rcCosine / distanceSquared;
}
public float reconnectionShiftJacobian(float dstGeometryTerm, float srcGeometryTerm) {
    return dstGeometryTerm / srcGeometryTerm;
}
public float spatialCanonicalInitialWeight(PairwiseFactor C) {
    return C.c_c / C.c_sum;
}
public float spatialCanonicalWeightTerm(PairwiseFactor C, float neighborM, float canonicalPHat,
                                        float canonicalAtOtherPHat) {
    float currentWeightTerm = 1.f;
    currentWeightTerm -=
            (canonicalAtOtherPHat > 0.f)
                    ? canonicalAtOtherPHat * (C.c_sum - C.c_c) /
                              (canonicalAtOtherPHat * (C.c_sum - C.c_c) + canonicalPHat * C.c_c)
                    : 0.f;
    return neighborM / C.c_sum * currentWeightTerm;
}
public float spatialNeighborWeight(PairwiseFactor C, float neighborM, float neighborPHat,
                                   float neighborAtCanonicalPHatWithJacobian) {
    float srcTerm = neighborPHat * (C.c_sum - C.c_c);
    return neighborM / C.c_sum * srcTerm / (srcTerm + neighborAtCanonicalPHatWithJacobian * C.c_c);
}
public float temporalCanonicalWeight(float canonicalM, float temporalM, float canonicalPHat,
                                     float canonicalAtTemporalPHatWithJacobian) {
    return (canonicalAtTemporalPHatWithJacobian > 0.f)
                   ? 1.f - canonicalAtTemporalPHatWithJacobian * temporalM /
                                     (canonicalAtTemporalPHatWithJacobian * temporalM +
                                      canonicalM * canonicalPHat)
                   : 1.f;
}
public float temporalNeighborWeight(float canonicalM, float temporalM, float temporalPHat,
                                    float temporalAtCanonicalPHatWithJacobian) {
    float srcTerm = temporalPHat * temporalM;
    return srcTerm / (srcTerm + temporalAtCanonicalPHatWithJacobian * canonicalM);
}
