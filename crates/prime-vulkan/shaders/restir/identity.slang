#language slang 2026
module "restir/identity.slang";
import "restir/reservoir.slang";

// Bounds and liveness are checked before any current scene pointer is dereferenced.
// All accepted reservoirs were validated in their accepted scene generation. A changed
// slot cannot reinterpret one of their stored HitInfo values as a different primitive.
public bool restirHistoryRecordValid(uint2 record, uint primitive, uint acceptedRevision) {
    return record.x != 0u && record.x <= acceptedRevision && primitive < record.y;
}
public bool restirHistoryHitValid(HitInfo hit, uint2 *staticRecords, uint2 *dynamicRecords,
                                  uint2 *emitterRecords, uint4 control) {
    if (!hit.isValid())
        return true; // An escape/environment reconnection has no finite identity.
    uint page = hit.pageID() & 0x00ffffffu;
    uint2 record;
    if ((hit.pageID() & 0x40000000u) != 0u) {
        if (page >= control.z)
            return false;
        record = emitterRecords[page];
    } else if ((page & 0x00800000u) != 0u) {
        page &= 0x007fffffu;
        if (page >= control.y)
            return false;
        record = dynamicRecords[page];
    } else {
        if (page >= control.x)
            return false;
        record = staticRecords[page];
    }
    return restirHistoryRecordValid(record, hit.primitiveIndex(), control.w);
}
