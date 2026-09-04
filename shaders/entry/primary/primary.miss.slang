#language slang 2026
module "entry/primary/primary.miss.slang";

import "contract/primary_color.slang";

layout(location = 0) rayPayloadInEXT PrimePrimaryColorPayload primePrimaryPayload;

[shader("miss")]
void main()
{
    primePrimaryPayload.hit = 0u;
}
