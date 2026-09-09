// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "model/atmosphere/celestial.slang";
import "prime_abi_types.slang";

static const float PRIME_PI = 3.14159265358979323846;
static const uint PRIME_CELESTIAL_INPUT_WORDS = 2;
static const uint PRIME_CELESTIAL_WITNESS_WORDS = 5;

float primeAngleDifference(float first, float second)
{
    return atan2(sin(first - second), cos(first - second));
}

float3 primeTestSunDirection(float latitude, float solarLongitude, float hourAngle)
{
    float declination = asin(
            sin(PRIME_ASTRONOMY_AXIAL_TILT_RADIANS) * sin(solarLongitude));
    float sineLatitude = sin(latitude);
    float cosineLatitude = cos(latitude);
    float sineDeclination = sin(declination);
    float cosineDeclination = cos(declination);
    return float3(
            -cosineDeclination * sin(hourAngle),
            sineLatitude * sineDeclination
                    + cosineLatitude * cosineDeclination * cos(hourAngle),
            -cosineLatitude * sineDeclination
                    + sineLatitude * cosineDeclination * cos(hourAngle));
}

float3 primeTestEquatorialDirection(
        PrimeCelestialFrame frame,
        float rightAscension,
        float declination)
{
    float hourAngle = frame.siderealPhase - rightAscension;
    float cosineDeclination = cos(declination);
    return frame.east * (-cosineDeclination * sin(hourAngle))
            + frame.meridian * (cosineDeclination * cos(hourAngle))
            + frame.pole * sin(declination);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_CELESTIAL_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1]);
    uint kind = control.x;
    float latitude = radians(float(int(control.y)));
    float solarLongitude = radians(float(control.z));
    float hourAngle = values.x;
    float3 sunDirection = primeTestSunDirection(latitude, solarLongitude, hourAngle);
    PrimeCelestialFrame frame = primeCelestialFrame(
            sunDirection, latitude, solarLongitude);
    uint mask = primeTestClassify(sunDirection)
            | primeTestClassify(frame.east)
            | primeTestClassify(frame.pole)
            | primeTestClassify(frame.meridian)
            | primeTestClassify(frame.siderealPhase);
    float4 first = float4(0.0);
    float4 second = float4(0.0);

    if (kind == 0) {
        first = float4(
                length(frame.east),
                length(frame.pole),
                length(frame.meridian),
                dot(frame.pole, float3(0.0, 1.0, 0.0)));
        second = float4(
                dot(frame.east, frame.pole),
                dot(frame.east, frame.meridian),
                dot(frame.pole, frame.meridian),
                sin(latitude));
        if (!primeTestClose(first.xyz, float3(1.0), 2.0e-6, 2.0e-6)
                || !primeTestClose(first.w, second.w, 2.0e-6, 2.0e-6)
                || !primeTestClose(second.xyz, float3(0.0), 2.0e-6, 2.0e-6)
                || frame.pole.z > 2.0e-6) {
            mask |= PRIME_TEST_FAILURE_ORTHOGONAL;
        }
    } else if (kind == 1) {
        float2 equatorial = primeCelestialEquatorialCoordinates(frame, sunDirection);
        float expectedRightAscension = primeSolarRightAscension(solarLongitude);
        float expectedDeclination = asin(
                sin(PRIME_ASTRONOMY_AXIAL_TILT_RADIANS) * sin(solarLongitude));
        first = float4(equatorial, expectedRightAscension, expectedDeclination);
        if (!primeTestClose(
                    primeAngleDifference(equatorial.x, expectedRightAscension),
                    0.0,
                    3.0e-6,
                    3.0e-6)
                || !primeTestClose(
                    equatorial.y, expectedDeclination, 3.0e-6, 3.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 2) {
        float3 direction = primeTestEquatorialDirection(frame, values.y, values.z);
        float2 roundTrip = primeCelestialEquatorialCoordinates(frame, direction);
        float3 reconstructed = primeTestEquatorialDirection(
                frame, roundTrip.x, roundTrip.y);
        bool stableCoordinates = abs(values.z) <= 1.4;
        first = float4(direction, length(direction));
        second = float4(roundTrip, values.y, values.z);
        mask |= primeTestClassify(direction) | primeTestClassify(roundTrip);
        if (!primeTestClose(length(direction), 1.0, 3.0e-6, 3.0e-6)
                || (stableCoordinates
                    && (!primeTestClose(
                            primeAngleDifference(roundTrip.x, values.y),
                            0.0,
                            1.0e-3,
                            0.0)
                        || !primeTestClose(roundTrip.y, values.z, 1.0e-3, 0.0)))
                || !primeTestClose(reconstructed, direction, 4.0e-3, 0.0)) {
            mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
        }
    } else if (kind == 3) {
        float3 nextSun = primeTestSunDirection(
                latitude, solarLongitude, hourAngle + 2.0 * PRIME_PI);
        PrimeCelestialFrame next = primeCelestialFrame(
                nextSun, latitude, solarLongitude);
        first = float4(sunDirection, frame.siderealPhase);
        second = float4(nextSun, next.siderealPhase);
        if (!primeTestClose(sunDirection, nextSun, 3.0e-6, 3.0e-6)
                || !primeTestClose(
                    primeAngleDifference(frame.siderealPhase, next.siderealPhase),
                    0.0,
                    3.0e-6,
                    3.0e-6)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else if (kind == 4) {
        float previousLongitude = radians(359.0);
        float3 previousSun = primeTestSunDirection(
                latitude, previousLongitude, hourAngle);
        PrimeCelestialFrame previous = primeCelestialFrame(
                previousSun, latitude, previousLongitude);
        float3 equinoxSun = primeTestSunDirection(latitude, 0.0, hourAngle);
        PrimeCelestialFrame equinox = primeCelestialFrame(
                equinoxSun, latitude, 0.0);
        float actual = primeAngleDifference(
                equinox.siderealPhase, previous.siderealPhase);
        float expected = primeAngleDifference(
                primeSolarRightAscension(0.0),
                primeSolarRightAscension(previousLongitude));
        first = float4(actual, expected, previous.siderealPhase, equinox.siderealPhase);
        if (!primeTestClose(actual, expected, 3.0e-6, 3.0e-6)
                || abs(actual) > radians(2.0)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_CELESTIAL_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = asuint(values);
        primeTestOutput[outputOffset + 3] = asuint(first);
        primeTestOutput[outputOffset + 4] = asuint(second);
    }
}
