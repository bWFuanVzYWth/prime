#language slang 2026
module "restir/identity.slang";
import "restir/reservoir.slang";

// RA-009, docs/restir-adaptations.md: check bounds/liveness before current-scene access.
// All accepted reservoirs were validated in their accepted scene generation. A changed
// slot cannot reinterpret one of their stored HitInfo values as a different primitive.
public bool restirHistoryRecordValid(uint2 record, uint primitive, uint acceptedRevision) {
    return record.x != 0u && record.x <= acceptedRevision && primitive < record.y;
}
public bool restirHistoryQuadValid(uint2 record, uint primitive, uint acceptedRevision) {
    return record.x != 0u && record.x <= acceptedRevision &&
           (record.y & (1u << (primitive & 1u))) != 0u;
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
        // A quad-mode header is an offset, not a legacy page revision. This
        // overload cannot validate its per-quad support without that table.
        if ((record.y & 0x80000000u) != 0u)
            return false;
    }
    return restirHistoryRecordValid(record, hit.primitiveIndex(), control.w);
}

public bool restirHistoryHitValid(HitInfo hit, uint2 *staticRecords, uint2 *dynamicRecords,
                                  uint2 *emitterRecords, uint4 control, uint2 *quadRecords) {
    uint page = hit.pageID() & 0x00ffffffu;
    if (hit.isValid() && (hit.pageID() & 0x40000000u) == 0u && (page & 0x00800000u) == 0u) {
        if (page >= control.x)
            return false;
        uint2 header = staticRecords[page];
        if ((header.y & 0x80000000u) != 0u) {
            uint primitive = hit.primitiveIndex();
            if (primitive >= (header.y & 0x7fffffffu))
                return false;
            uint offset = header.x + (primitive >> 1u);
            return restirHistoryQuadValid(quadRecords[offset], primitive, control.w);
        }
    }
    return restirHistoryHitValid(hit, staticRecords, dynamicRecords, emitterRecords, control);
}
