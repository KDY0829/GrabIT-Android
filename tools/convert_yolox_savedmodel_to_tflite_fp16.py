from __future__ import annotations

from pathlib import Path

import tensorflow as tf


REPO_ROOT = Path(__file__).resolve().parents[1]
SAVED_MODEL_DIR = REPO_ROOT / "model_conversion/yolox_saved_model"
OUT_PATH = REPO_ROOT / "app/src/main/assets/yolox_nano_retail_640_balanced_split_fp16.tflite"


def main() -> None:
    if not SAVED_MODEL_DIR.exists():
        raise FileNotFoundError(SAVED_MODEL_DIR)

    converter = tf.lite.TFLiteConverter.from_saved_model(str(SAVED_MODEL_DIR))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]

    tflite_model = converter.convert()
    OUT_PATH.write_bytes(tflite_model)
    print("saved:", OUT_PATH.relative_to(REPO_ROOT))
    print("size:", OUT_PATH.stat().st_size)


if __name__ == "__main__":
    main()
