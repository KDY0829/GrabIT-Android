from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper


REPO_ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = REPO_ROOT / "app/src/main/assets/yolox_nano_retail_640_balanced_split_opset18.onnx"
REPORT_PATH = REPO_ROOT / "model_conversion/yolox_onnx_inspect_report.md"
JSON_PATH = REPO_ROOT / "model_conversion/yolox_onnx_io_info.json"


def tensor_dtype_name(elem_type: int) -> str:
    return TensorProto.DataType.Name(elem_type)


def dim_to_value(dim: onnx.TensorShapeProto.Dimension) -> int | str | None:
    if dim.HasField("dim_value"):
        return dim.dim_value
    if dim.HasField("dim_param"):
        return dim.dim_param
    return None


def value_info_to_dict(value_info: onnx.ValueInfoProto) -> dict[str, Any]:
    tensor_type = value_info.type.tensor_type
    return {
        "name": value_info.name,
        "shape": [dim_to_value(dim) for dim in tensor_type.shape.dim],
        "dtype": tensor_dtype_name(tensor_type.elem_type),
    }


def summarize_initializers(model: onnx.ModelProto) -> dict[str, Any]:
    dtype_counts: Counter[str] = Counter()
    nan_initializers: list[str] = []
    inf_initializers: list[str] = []
    checked = 0

    for initializer in model.graph.initializer:
        dtype_counts[tensor_dtype_name(initializer.data_type)] += 1
        try:
            array = numpy_helper.to_array(initializer)
        except Exception:
            continue
        if np.issubdtype(array.dtype, np.floating):
            checked += 1
            if np.isnan(array).any():
                nan_initializers.append(initializer.name)
            if np.isinf(array).any():
                inf_initializers.append(initializer.name)

    return {
        "count": len(model.graph.initializer),
        "dtype_counts": dict(sorted(dtype_counts.items())),
        "floating_initializers_checked": checked,
        "nan_initializers": nan_initializers,
        "inf_initializers": inf_initializers,
    }


def infer_yolox_output_kind(outputs: list[dict[str, Any]]) -> str:
    if not outputs:
        return "unknown"
    shape = outputs[0].get("shape") or []
    numeric_dims = [dim for dim in shape if isinstance(dim, int)]
    if len(numeric_dims) >= 2:
        last = numeric_dims[-1]
        prev = numeric_dims[-2]
        if prev in (8400, 3549, 2100) and last >= 5:
            return "decoded_or_flattened_yolox_candidates"
        if last <= 100 and prev >= 1000:
            return "decoded_or_flattened_yolox_candidates"
        if last <= 100 and prev <= 100:
            return "likely_nms_or_topk_detections"
    return "unknown"


def main() -> None:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(MODEL_PATH)

    model = onnx.load(str(MODEL_PATH))
    onnx.checker.check_model(model)

    inputs = [value_info_to_dict(item) for item in model.graph.input]
    initializer_names = {item.name for item in model.graph.initializer}
    real_inputs = [item for item in inputs if item["name"] not in initializer_names]
    outputs = [value_info_to_dict(item) for item in model.graph.output]
    opsets = [{"domain": item.domain or "ai.onnx", "version": item.version} for item in model.opset_import]
    initializer_summary = summarize_initializers(model)

    file_size = MODEL_PATH.stat().st_size
    info: dict[str, Any] = {
        "model_path": str(MODEL_PATH.relative_to(REPO_ROOT)),
        "file_size_bytes": file_size,
        "file_size_mb": round(file_size / 1024 / 1024, 3),
        "opsets": opsets,
        "inputs": real_inputs,
        "all_graph_inputs": inputs,
        "outputs": outputs,
        "initializer_summary": initializer_summary,
        "output_kind_guess": infer_yolox_output_kind(outputs),
    }

    JSON_PATH.parent.mkdir(parents=True, exist_ok=True)
    JSON_PATH.write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")

    lines = [
        "# YOLOX ONNX Inspect Report",
        "",
        f"- model: `{info['model_path']}`",
        f"- size: {info['file_size_bytes']} bytes ({info['file_size_mb']} MiB)",
        f"- opsets: {opsets}",
        f"- output kind guess: {info['output_kind_guess']}",
        "",
        "## Inputs",
    ]
    for item in real_inputs:
        lines.append(f"- `{item['name']}`: shape={item['shape']} dtype={item['dtype']}")
    lines.extend(["", "## Outputs"])
    for item in outputs:
        lines.append(f"- `{item['name']}`: shape={item['shape']} dtype={item['dtype']}")
    lines.extend(
        [
            "",
            "## Initializers",
            f"- count: {initializer_summary['count']}",
            f"- dtype counts: {initializer_summary['dtype_counts']}",
            f"- floating initializers checked: {initializer_summary['floating_initializers_checked']}",
            f"- NaN initializers: {initializer_summary['nan_initializers'] or 'none'}",
            f"- Inf initializers: {initializer_summary['inf_initializers'] or 'none'}",
        ]
    )

    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {REPORT_PATH.relative_to(REPO_ROOT)}")
    print(f"wrote {JSON_PATH.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
