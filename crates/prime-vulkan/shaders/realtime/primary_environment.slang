#language slang 2026
module "realtime/primary_environment.slang";
import "atmosphere/lighting.slang";

public interface IPrimaryEnvironment {
    float3 escape(float3 direction, bool initialCameraMiss);
}

public struct PrimaryEnvironment : IPrimaryEnvironment {
    public AtmLighting lighting;
    public float sunIntensity;
    public float skyIntensity;
    public bool omitCameraStars;
    public float3 escape(float3 direction, bool initialCameraMiss) {
        float3 result = omitCameraStars && initialCameraMiss
                                ? atmSkyWithoutStars(lighting, direction) * skyIntensity
                                : atmSky(lighting, direction, skyIntensity);
        if (atmSunContains(lighting, direction))
            result += atmSunRadiance(lighting, direction) * sunIntensity;
        return result;
    }
}
