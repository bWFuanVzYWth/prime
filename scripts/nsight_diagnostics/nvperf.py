"""Reviewed 64-bit graphics NVPerf host ABI, evaluated in a child process.

Only the functions listed here are called. No target library, driver, device,
capture/replay, or sampling-session API is loaded or initialized.
"""

import ctypes as C
import csv
import math
import os
import sys
from pathlib import Path

from . import FormatError
from .analysis import aggregate
from .format import sha256, write_json


class Init(C.Structure):
    _fields_ = [("structSize", C.c_size_t), ("pPriv", C.c_void_p)]


class Chip(C.Structure):
    _fields_ = Init._fields_ + [("pCounterDataImage", C.c_void_p), ("counterDataImageSize", C.c_size_t),
                               ("pChipName", C.c_char_p)]


class Ranges(C.Structure):
    _fields_ = Init._fields_ + [("pCounterDataImage", C.c_void_p), ("numRanges", C.c_size_t)]


class Info(C.Structure):
    _fields_ = Init._fields_ + [("pCounterDataImage", C.c_void_p), ("counterDataImageSize", C.c_size_t),
                               ("numTotalRanges", C.c_size_t), ("numPopulatedRanges", C.c_size_t),
                               ("numCompletedRanges", C.c_size_t)]


class SampleTime(C.Structure):
    _fields_ = Init._fields_ + [("pCounterDataImage", C.c_void_p), ("rangeIndex", C.c_size_t),
                               ("timestampStart", C.c_uint64), ("timestampEnd", C.c_uint64)]


class ScratchSize(C.Structure):
    _fields_ = Init._fields_ + [("pChipName", C.c_char_p), ("scratchBufferSize", C.c_size_t)]


class EvaluatorInit(C.Structure):
    _fields_ = Init._fields_ + [("pScratchBuffer", C.c_void_p), ("scratchBufferSize", C.c_size_t),
                               ("pChipName", C.c_char_p), ("pCounterDataImage", C.c_void_p),
                               ("counterDataImageSize", C.c_size_t), ("pMetricsEvaluator", C.c_void_p)]


class Request(C.Structure):
    _fields_ = [("metricIndex", C.c_size_t), ("metricType", C.c_uint8),
               ("rollupOp", C.c_uint8), ("submetric", C.c_uint16)]


REQUEST_SIZE = Request.submetric.offset + C.sizeof(C.c_uint16)


class Convert(C.Structure):
    _fields_ = Init._fields_ + [("pMetricsEvaluator", C.c_void_p), ("pMetricName", C.c_char_p),
                               ("pMetricEvalRequest", C.POINTER(Request)),
                               ("metricEvalRequestStructSize", C.c_size_t)]


class Evaluate(C.Structure):
    _fields_ = Init._fields_ + [("pMetricsEvaluator", C.c_void_p), ("pMetricEvalRequests", C.POINTER(Request)),
                               ("numMetricEvalRequests", C.c_size_t), ("metricEvalRequestStructSize", C.c_size_t),
                               ("metricEvalRequestStrideSize", C.c_size_t), ("pCounterDataImage", C.c_void_p),
                               ("counterDataImageSize", C.c_size_t), ("rangeIndex", C.c_size_t),
                               ("isolated", C.c_uint8), ("pMetricValues", C.POINTER(C.c_double))]


class Destroy(C.Structure):
    _fields_ = Init._fields_ + [("pMetricsEvaluator", C.c_void_p)]


class DeviceAttributes(C.Structure):
    _fields_ = Init._fields_ + [("pMetricsEvaluator", C.c_void_p), ("pCounterDataImage", C.c_void_p),
                               ("counterDataImageSize", C.c_size_t)]


FUNCTIONS = {
    "NVPW_InitializeHost": Init,
    "NVPW_CounterData_GetChipName": Chip,
    "NVPW_CounterData_GetNumRanges": Ranges,
    "NVPW_PeriodicSampler_CounterData_GetInfo": Info,
    "NVPW_PeriodicSampler_CounterData_GetSampleTime": SampleTime,
    "NVPW_Device_MetricsEvaluator_CalculateScratchBufferSize": ScratchSize,
    "NVPW_Device_MetricsEvaluator_Initialize": EvaluatorInit,
    "NVPW_MetricsEvaluator_SetDeviceAttributes": DeviceAttributes,
    "NVPW_MetricsEvaluator_ConvertMetricNameToMetricEvalRequest": Convert,
    "NVPW_MetricsEvaluator_EvaluateToGpuValues": Evaluate,
    "NVPW_MetricsEvaluator_Destroy": Destroy,
}


def evaluate_counter_image(host, image_path, windows, names, out):
    if sys.platform != "win32" or C.sizeof(C.c_void_p) != 8:
        raise FormatError("NVPerf host ABI currently supports Windows x64 only; counter image is still exported")
    dll = host / "nvperf_grfx_host.dll"
    # The reviewed descriptor profile is checked by the parent before reaching
    # this worker. ctypes structure-size negotiation must succeed in the host.
    with os.add_dll_directory(str(host)):
        library = C.CDLL(str(dll))
        for name, cls in FUNCTIONS.items():
            function = getattr(library, name)
            function.argtypes = [C.POINTER(cls)]
            function.restype = C.c_int

        def call(name, params):
            params.structSize = C.sizeof(params)
            return getattr(library, name)(C.byref(params))

        def checked(name, params):
            status = call(name, params)
            if status:
                raise FormatError(f"{name} failed with NVPerf status {status}")
            return params

        checked("NVPW_InitializeHost", Init())
        data = image_path.read_bytes()
        storage = C.create_string_buffer(data)
        pointer = C.cast(storage, C.c_void_p)
        chip = checked("NVPW_CounterData_GetChipName", Chip(pCounterDataImage=pointer, counterDataImageSize=len(data)))
        if not chip.pChipName:
            raise FormatError("counter image has no chip name")
        chip_name = chip.pChipName.decode("utf-8")
        size = checked("NVPW_Device_MetricsEvaluator_CalculateScratchBufferSize", ScratchSize(pChipName=chip.pChipName))
        if size.scratchBufferSize > 512 * 1024**2:
            raise FormatError("NVPerf scratch buffer exceeds size limit")
        scratch = C.create_string_buffer(size.scratchBufferSize)
        init = checked("NVPW_Device_MetricsEvaluator_Initialize", EvaluatorInit(
            pScratchBuffer=C.cast(scratch, C.c_void_p), scratchBufferSize=size.scratchBufferSize,
            pChipName=chip.pChipName, pCounterDataImage=pointer, counterDataImageSize=len(data)))
        try:
            checked("NVPW_MetricsEvaluator_SetDeviceAttributes", DeviceAttributes(
                pMetricsEvaluator=init.pMetricsEvaluator, pCounterDataImage=pointer, counterDataImageSize=len(data)))
            count = checked("NVPW_CounterData_GetNumRanges", Ranges(pCounterDataImage=pointer))
            info = checked("NVPW_PeriodicSampler_CounterData_GetInfo", Info(pCounterDataImage=pointer, counterDataImageSize=len(data)))
            if count.numRanges > 1_000_000:
                raise FormatError("counter image has too many ranges")
            requests, accepted, failures = [], [], []
            for name in names:
                request = Request()
                status = call("NVPW_MetricsEvaluator_ConvertMetricNameToMetricEvalRequest", Convert(
                    pMetricsEvaluator=init.pMetricsEvaluator, pMetricName=name.encode("utf-8"),
                    pMetricEvalRequest=C.pointer(request), metricEvalRequestStructSize=REQUEST_SIZE))
                if status:
                    failures.append({"metric": name, "status": status, "reason": "metric-name conversion failed"})
                else:
                    requests.append(request)
                    accepted.append(name)
            array = (Request * len(requests))(*requests)
            values = (C.c_double * len(requests))()
            rows, bad_ranges, status_counts = [], [], {}
            for index in range(count.numRanges):
                time = SampleTime(pCounterDataImage=pointer, rangeIndex=index)
                status = call("NVPW_PeriodicSampler_CounterData_GetSampleTime", time)
                if status or time.timestampEnd <= time.timestampStart:
                    bad_ranges.append({"range": index, "status": status, "reason": "unavailable/nonpositive sample time"})
                    continue
                status = call("NVPW_MetricsEvaluator_EvaluateToGpuValues", Evaluate(
                    pMetricsEvaluator=init.pMetricsEvaluator, pMetricEvalRequests=array, numMetricEvalRequests=len(requests),
                    metricEvalRequestStructSize=REQUEST_SIZE, metricEvalRequestStrideSize=C.sizeof(Request),
                    pCounterDataImage=pointer, counterDataImageSize=len(data), rangeIndex=index, isolated=0,
                    pMetricValues=values)) if requests else None
                row = {"range": index, "start": time.timestampStart, "end": time.timestampEnd, "evaluateStatus": status}
                for name, value in zip(accepted, values):
                    row[name] = value if status == 0 and math.isfinite(value) else None
                if status:
                    status_counts[str(status)] = status_counts.get(str(status), 0) + 1
                rows.append(row)
            with (out / "counter-samples.csv").open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, ["range", "start", "end", "evaluateStatus", *names])
                writer.writeheader()
                writer.writerows(rows)
            summaries = []
            for window in [*windows["frames"], *windows["dispatches"]]:
                if window["durationNs"] is not None:
                    summaries.append({"windowId": window["id"],
                                      **aggregate(rows, window["startNs"], window["endNs"], names)})
            unavailable = [name for name in names if not any(row.get(name) is not None for row in rows)]
            return {"status": "partial" if failures or bad_ranges or status_counts or unavailable else "decoded",
                    "chip": chip_name, "library": {"path": str(dll), "sha256": sha256(dll.read_bytes())},
                    "abiProfile": "graphics-host-size-t64-v1", "calledFunctions": list(FUNCTIONS),
                    "requestStructBytes": REQUEST_SIZE, "requestStrideBytes": C.sizeof(Request),
                    "totalRanges": count.numRanges, "rangeInfo": {"total": info.numTotalRanges,
                    "populated": info.numPopulatedRanges, "completed": info.numCompletedRanges},
                    "conversionFailures": failures, "badRanges": bad_ranges, "evaluationStatusCounts": status_counts,
                    "requestedMetrics": names, "unavailableMetrics": unavailable, "windows": summaries,
                    "limitations": ["Nonfinite/missing metrics are null, never zero.",
                    "Means are overlap-duration weighted; sums assume uniform counts within each periodic sample.",
                    "Prorated counts can be fractional. Missing coverage is not extrapolated.",
                    "Units are inferred only for recognized metric name suffixes; unknown units remain unknown."]}
        finally:
            status = call("NVPW_MetricsEvaluator_Destroy", Destroy(pMetricsEvaluator=init.pMetricsEvaluator))
            if status:
                raise FormatError(f"NVPerf evaluator cleanup failed, status {status}")
