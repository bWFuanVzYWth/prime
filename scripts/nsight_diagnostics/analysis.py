"""GPU timestamp windows and explicit overlap aggregation; no CPU durations."""

import math

from . import FormatError
from .format import brief


DEFAULT_METRICS = [
    "gpu__time_duration.sum",
    "TriageSCG.sm__throughput.avg.pct_of_peak_sustained_elapsed",
    "sm__inst_executed.avg.pct_of_peak_sustained_elapsed",
    "lts__throughput.avg.pct_of_peak_sustained_elapsed",
    "LTS.TriageSCG.lts__throughput.avg.pct_of_peak_sustained_elapsed",
    "dramc__throughput.avg.pct_of_peak_sustained_elapsed",
    "dramc__read_throughput.avg.pct_of_peak_sustained_elapsed",
    "dramc__write_throughput.avg.pct_of_peak_sustained_elapsed",
    "pcie__throughput.avg.pct_of_peak_sustained_elapsed",
    "rtcore__cycles_executed.avg.pct_of_peak_sustained_elapsed",
    "rtcore__cycles_executed.sum",
    "tpc__warps_active_shader_cs_realtime.avg.pct_of_peak_sustained_elapsed",
    "tpc__warps_active_shader_cs_realtime.avg.per_cycle_elapsed",
    "tpc__warps_launched_shader_cs.sum",
    "tpc__warp_launch_cycles_stalled_shader_cs_reason_register_allocation.avg.pct_of_peak_sustained_elapsed",
    "smsp__inst_executed.sum",
    "smsp__thread_inst_executed.sum",
    "smsp__average_thread_inst_executed_per_inst_executed.pct",
    "lts__average_t_sector_hit_rate_realtime.pct",
    "lts__t_bytes_op_read.sum",
    "lts__t_bytes_op_write.sum",
    "lts__t_sectors.sum",
    "l1tex__t_bytes_pipe_lsu_mem_local_op_ld.sum",
    "l1tex__t_bytes_pipe_lsu_mem_local_op_st.sum",
]


def metric_unit(name):
    if name.endswith(".pct") or ".pct_of_peak_" in name:
        return "percent"
    if name.endswith(".per_cycle_elapsed") or name.endswith(".per_cycle_active"):
        return "per_cycle"
    if name.endswith(".sum"):
        if "bytes" in name:
            return "bytes"
        if "time_duration" in name:
            return "nanoseconds"
        if "cycles" in name:
            return "cycles"
        if "inst_executed" in name:
            return "instructions"
        if "warps" in name:
            return "warps"
        if "sectors" in name:
            return "sectors"
    return "unknown (metric-name inference unavailable)"


def aggregate(rows, start, end, names):
    if end <= start:
        raise FormatError("aggregation window must have positive duration")
    ordered = sorted(rows, key=lambda row: row["start"])
    previous_end = None
    overlaps = []
    for row in ordered:
        if row["end"] <= row["start"]:
            raise FormatError("periodic counter sample has nonpositive duration")
        if previous_end is not None and row["start"] < previous_end:
            raise FormatError("overlapping counter ranges: aggregation would double-count coverage")
        previous_end = row["end"]
        ns = max(0, min(row["end"], end) - max(row["start"], start))
        if ns:
            overlaps.append((row, ns))
    covered = sum(ns for _, ns in overlaps)
    result = {"startNs": start, "endNs": end, "durationNs": end - start, "coveredNs": covered,
              "coverage": covered / (end - start), "sampleCount": len(overlaps), "metrics": {}}
    for name in names:
        finite = [(row[name], ns, row["end"] - row["start"]) for row, ns in overlaps
                  if row.get(name) is not None and math.isfinite(row[name])]
        duration = sum(ns for _, ns, _ in finite)
        result["metrics"][name] = {
            "unit": metric_unit(name), "unitSource": "metricNameSuffix",
            "durationWeightedMean": sum(value * ns for value, ns, _ in finite) / duration if duration else None,
            "min": min((value for value, _, _ in finite), default=None),
            "max": max((value for value, _, _ in finite), default=None),
            "validDurationNs": duration, "finiteSampleCount": len(finite),
            "overlapProratedSum": (sum(value * ns / interval for value, ns, interval in finite)
                                   if finite and name.endswith(".sum") else None),
        }
    paired = [(row, ns) for row, ns in overlaps
              if all(row.get(key) is not None and math.isfinite(row[key])
                     for key in ("smsp__inst_executed.sum", "smsp__thread_inst_executed.sum"))]
    warp = sum(row["smsp__inst_executed.sum"] * ns / (row["end"] - row["start"]) for row, ns in paired)
    thread = sum(row["smsp__thread_inst_executed.sum"] * ns / (row["end"] - row["start"]) for row, ns in paired)
    result["threadInstOver32WarpInst"] = thread / (32 * warp) if paired and warp > 0 else None
    result["threadWarpPairedCoverage"] = {"validDurationNs": sum(ns for _, ns in paired),
                                        "sampleCount": len(paired),
                                        "proratedWarpInstructions": warp if paired else None,
                                        "proratedThreadInstructions": thread if paired else None}
    return result


def argument(call, name):
    values = [item for item in call.arguments if item.name == name]
    return values[0] if len(values) == 1 else None


def gpu_windows(trace):
    frames, dispatches, streams = [], [], []
    for di, device in enumerate(trace.devices):
        for swi, swapchain in enumerate(device.swapchains):
            for fi, frame in enumerate(swapchain.Frames):
                if frame.End <= frame.Start:
                    raise FormatError("captured GPU frame has nonpositive duration")
                frames.append({"id": f"device-{di}/swapchain-{swi}/frame-{fi}", "device": di,
                               "swapchain": swi, "frame": fi, "startNs": frame.Start, "endNs": frame.End,
                               "durationNs": frame.End - frame.Start})
        for qi, queue in enumerate(device.CommandQueues):
            for si, stream in enumerate(queue.CommandStreams):
                # Values can contain more than one clock domain. Do not guess.
                times, ambiguous, invalid_indices = {}, set(), []
                for timestamp in stream.Timestamps:
                    ci = timestamp.NextCallIndex
                    if ci > len(stream.Calls):
                        invalid_indices.append(ci)
                        continue
                    if len(timestamp.Values) != 1 or ci in times:
                        ambiguous.add(ci)
                    elif timestamp.Values[0].HasField("PtimerValue"):
                        times[ci] = timestamp.Values[0].PtimerValue
                for ci in ambiguous:
                    times.pop(ci, None)
                bindings, selected = {}, []
                for ci, call in enumerate(stream.Calls):
                    if call.functionName == "vkCmdBindPipeline":
                        bind, pipeline = argument(call, "pipelineBindPoint"), argument(call, "pipeline")
                        if bind is not None and pipeline is not None:
                            bindings[bind.int32Value.value] = pipeline.handleValue.handle
                    if not ("Dispatch" in call.functionName or "TraceRays" in call.functionName):
                        continue
                    begin, end = times.get(ci), times.get(ci + 1)
                    ray = "TraceRays" in call.functionName
                    item = {"id": f"device-{di}/queue-{qi}/stream-{si}/call-{ci}", "device": di,
                            "queue": qi, "stream": si, "call": ci, "function": call.functionName,
                            "pipeline": bindings.get(1000165000 if ray else 1), "parameters": brief(call),
                            "parameterReliability": "rawCaptureParameters/unvalidated; never corrected from expected source",
                            "startNs": begin, "endNs": end, "durationNs": None}
                    if begin is not None and end is not None and end > begin:
                        item["durationNs"] = end - begin
                    else:
                        item["unavailableReason"] = "no unambiguous positive exact GPU timestamps before/after call"
                    dispatches.append(item)
                    selected.append(ci)
                streams.append({"device": di, "queue": qi, "stream": si, "workCalls": selected,
                                "ambiguousTimestampCallIndices": sorted(ambiguous),
                                "outOfRangeTimestampCallIndices": invalid_indices})
    for dispatch in dispatches:
        dispatch["containedFrameIds"] = [frame["id"] for frame in frames if frame["device"] == dispatch["device"] and
            dispatch["durationNs"] is not None and frame["startNs"] <= dispatch["startNs"] and dispatch["endNs"] <= frame["endNs"]]
    return {"timeUnit": "nanoseconds", "clock": "GPU Ptimer (reviewed WRPV profile)",
            "frames": frames, "dispatches": dispatches, "streams": streams,
            "limitations": ["Call intervals are GPU timestamp walls, not CPU call durations or exclusive shader time.",
                            "Concurrent queues/windows may overlap; durations are not summed into frame work."]}
