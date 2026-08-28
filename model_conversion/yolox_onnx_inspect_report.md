# YOLOX ONNX Inspect Report

- model: `app\src\main\assets\yolox_nano_retail_640_balanced_split_opset18.onnx`
- size: 9025158 bytes (8.607 MiB)
- opsets: [{'domain': 'ai.onnx', 'version': 18}]
- output kind guess: decoded_or_flattened_yolox_candidates

## Inputs
- `images`: shape=[1, 3, 640, 640] dtype=FLOAT

## Outputs
- `output`: shape=[1, 8400, 48] dtype=FLOAT

## Initializers
- count: 177
- dtype counts: {'FLOAT': 167, 'INT64': 10}
- floating initializers checked: 167
- NaN initializers: none
- Inf initializers: none
