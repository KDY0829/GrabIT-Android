from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
import tensorflow as tf


REPO_ROOT = Path(__file__).resolve().parents[1]


def detail_to_dict(detail: dict[str, Any]) -> dict[str, Any]:
    quantization_parameters = detail.get("quantization_parameters", {})
    return {
        "name": detail.get("name"),
        "index": int(detail.get("index")),
        "shape": np.asarray(detail.get("shape")).astype(int).tolist(),
        "shape_signature": np.asarray(detail.get("shape_signature")).astype(int).tolist(),
        "dtype": np.dtype(detail.get("dtype")).name,
        "quantization": tuple(float(x) for x in detail.get("quantization", (0.0, 0))),
        "quantization_parameters": {
            "scales": np.asarray(quantization_parameters.get("scales", [])).astype(float).tolist(),
            "zero_points": np.asarray(quantization_parameters.get("zero_points", [])).astype(int).tolist(),
            "quantized_dimension": int(quantization_parameters.get("quantized_dimension", 0)),
        },
    }


def write_report(info: dict[str, Any], out_prefix: Path) -> None:
    lines = [
        "# YOLOX TFLite Inspect Report",
        "",
        f"- model: `{info['model_path']}`",
        f"- size: {info['file_size_bytes']} bytes ({info['file_size_mb']} MiB)",
        "",
        "## Inputs",
    ]
    for item in info["inputs"]:
        lines.append(
            f"- `{item['name']}` index={item['index']} shape={item['shape']} "
            f"shape_signature={item['shape_signature']} dtype={item['dtype']}"
        )
    lines.extend(["", "## Outputs"])
    for item in info["outputs"]:
        lines.append(
            f"- `{item['name']}` index={item['index']} shape={item['shape']} "
            f"shape_signature={item['shape_signature']} dtype={item['dtype']}"
        )

    report_path = out_prefix.with_name(out_prefix.name + "_report.md")
    json_path = out_prefix.with_name(out_prefix.name + "_io_info.json")
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    json_path.write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {report_path.relative_to(REPO_ROOT)}")
    print(f"wrote {json_path.relative_to(REPO_ROOT)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("--out-prefix", type=Path, required=True)
    args = parser.parse_args()

    model_path = args.model if args.model.is_absolute() else REPO_ROOT / args.model
    out_prefix = args.out_prefix if args.out_prefix.is_absolute() else REPO_ROOT / args.out_prefix
    if not model_path.exists():
        raise FileNotFoundError(model_path)

    interpreter = tf.lite.Interpreter(model_content=model_path.read_bytes())
    interpreter.allocate_tensors()
    inputs = [detail_to_dict(item) for item in interpreter.get_input_details()]
    outputs = [detail_to_dict(item) for item in interpreter.get_output_details()]
    size = model_path.stat().st_size
    info = {
        "model_path": str(model_path.relative_to(REPO_ROOT)),
        "file_size_bytes": size,
        "file_size_mb": round(size / 1024 / 1024, 3),
        "inputs": inputs,
        "outputs": outputs,
    }
    out_prefix.parent.mkdir(parents=True, exist_ok=True)
    write_report(info, out_prefix)


if __name__ == "__main__":
    main()
