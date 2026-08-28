from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
import tensorflow as tf


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ONNX = REPO_ROOT / "app/src/main/assets/yolox_nano_retail_640_balanced_split_opset18.onnx"
DEFAULT_TFLITE = REPO_ROOT / "app/src/main/assets/yolox_nano_retail_640_balanced_split_fp16.tflite"
REPORT_PATH = REPO_ROOT / "model_conversion/yolox_tflite_parity_report.md"


def make_input(shape: list[int], dtype: np.dtype, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    data = rng.uniform(0.0, 255.0, size=shape).astype(np.float32)
    return data.astype(dtype, copy=False)


def flatten_output(output: Any) -> np.ndarray:
    return np.asarray(output).astype(np.float32).reshape(-1)


def describe_array(array: np.ndarray) -> dict[str, Any]:
    finite = np.isfinite(array)
    return {
        "shape": list(array.shape),
        "dtype": str(array.dtype),
        "min": float(np.nanmin(array)) if array.size else None,
        "max": float(np.nanmax(array)) if array.size else None,
        "mean": float(np.nanmean(array)) if array.size else None,
        "nan_count": int(np.isnan(array).sum()),
        "inf_count": int(np.isinf(array).sum()),
        "finite_count": int(finite.sum()),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", type=Path, default=DEFAULT_ONNX)
    parser.add_argument("--tflite", type=Path, default=DEFAULT_TFLITE)
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()

    onnx_path = args.onnx if args.onnx.is_absolute() else REPO_ROOT / args.onnx
    tflite_path = args.tflite if args.tflite.is_absolute() else REPO_ROOT / args.tflite
    if not onnx_path.exists():
        raise FileNotFoundError(onnx_path)
    if not tflite_path.exists():
        raise FileNotFoundError(tflite_path)

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_input = session.get_inputs()[0]
    onnx_input_shape = [int(dim) for dim in onnx_input.shape]
    onnx_input_name = onnx_input.name

    interpreter = tf.lite.Interpreter(model_content=tflite_path.read_bytes())
    interpreter.allocate_tensors()
    tflite_input = interpreter.get_input_details()[0]
    tflite_outputs = interpreter.get_output_details()
    tflite_shape = [int(dim) for dim in tflite_input["shape"]]
    tflite_dtype = np.dtype(tflite_input["dtype"])

    sample_lines: list[str] = []
    diffs: list[tuple[float, float]] = []
    compatible_shapes = True
    any_nan_inf = False

    for seed in range(args.samples):
        onnx_data = make_input(onnx_input_shape, np.float32, seed)
        tflite_data = onnx_data
        if tflite_shape != onnx_input_shape:
            if sorted(tflite_shape) == sorted(onnx_input_shape) and len(tflite_shape) == 4:
                if onnx_input_shape[1] == 3 and tflite_shape[-1] == 3:
                    tflite_data = np.transpose(onnx_data, (0, 2, 3, 1))
                elif onnx_input_shape[-1] == 3 and tflite_shape[1] == 3:
                    tflite_data = np.transpose(onnx_data, (0, 3, 1, 2))
                else:
                    compatible_shapes = False
            else:
                compatible_shapes = False
        tflite_data = tflite_data.astype(tflite_dtype, copy=False)

        onnx_outputs = session.run(None, {onnx_input_name: onnx_data})
        interpreter.set_tensor(tflite_input["index"], tflite_data)
        interpreter.invoke()
        tflite_values = [interpreter.get_tensor(item["index"]) for item in tflite_outputs]

        if len(onnx_outputs) != len(tflite_values):
            compatible_shapes = False
        for idx, (onnx_out, tflite_out) in enumerate(zip(onnx_outputs, tflite_values)):
            onnx_arr = np.asarray(onnx_out)
            tflite_arr = np.asarray(tflite_out)
            same_shape = list(onnx_arr.shape) == list(tflite_arr.shape)
            compatible_shapes = compatible_shapes and same_shape
            any_nan_inf = any_nan_inf or not np.isfinite(onnx_arr).all() or not np.isfinite(tflite_arr).all()
            if same_shape:
                diff = np.abs(flatten_output(onnx_arr) - flatten_output(tflite_arr))
                mean_abs = float(diff.mean()) if diff.size else 0.0
                max_abs = float(diff.max()) if diff.size else 0.0
                diffs.append((mean_abs, max_abs))
            else:
                mean_abs = float("nan")
                max_abs = float("nan")
            sample_lines.append(
                f"- sample {seed} output {idx}: onnx={describe_array(onnx_arr)} "
                f"tflite={describe_array(tflite_arr)} mean_abs_diff={mean_abs} max_abs_diff={max_abs}"
            )

    mean_diff = float(np.mean([item[0] for item in diffs])) if diffs else float("nan")
    max_diff = float(np.max([item[1] for item in diffs])) if diffs else float("nan")
    lines = [
        "# YOLOX ONNX vs TFLite Parity Report",
        "",
        f"- onnx: `{onnx_path.relative_to(REPO_ROOT)}`",
        f"- tflite: `{tflite_path.relative_to(REPO_ROOT)}`",
        f"- tested input: random uniform 0..255, samples={args.samples}",
        f"- onnx input: name={onnx_input_name} shape={onnx_input_shape} dtype={onnx_input.type}",
        f"- tflite input: shape={tflite_shape} dtype={tflite_dtype.name}",
        f"- compatible shapes: {compatible_shapes}",
        f"- mean abs diff: {mean_diff}",
        f"- max abs diff: {max_diff}",
        f"- NaN/Inf found: {any_nan_inf}",
        "",
        "## Samples",
        *sample_lines,
    ]
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {REPORT_PATH.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
