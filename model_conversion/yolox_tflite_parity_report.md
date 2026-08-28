# YOLOX ONNX vs TFLite Parity Report

- onnx: `app\src\main\assets\yolox_nano_retail_640_balanced_split_opset18.onnx`
- tflite: `app\src\main\assets\yolox_nano_retail_640_balanced_split_fp16.tflite`
- tested input: random uniform 0..255, samples=3
- onnx input: name=images shape=[1, 3, 640, 640] dtype=tensor(float)
- tflite input: shape=[1, 640, 640, 3] dtype=float32
- compatible shapes: True
- mean abs diff: 4.6453390192861356e-05
- max abs diff: 0.00227510929107666
- NaN/Inf found: False

## Samples
- sample 0 output 0: onnx={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.1661063432693481, 'max': 3.345935344696045, 'mean': 0.11322515457868576, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} tflite={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.165791630744934, 'max': 3.3457090854644775, 'mean': 0.1132366731762886, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} mean_abs_diff=4.6185043174773455e-05 max_abs_diff=0.002113163471221924
- sample 1 output 0: onnx={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.1024017333984375, 'max': 3.338639259338379, 'mean': 0.11365529894828796, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} tflite={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.1023293733596802, 'max': 3.338397979736328, 'mean': 0.11366772651672363, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} mean_abs_diff=4.699113924289122e-05 max_abs_diff=0.00227510929107666
- sample 2 output 0: onnx={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.0701932907104492, 'max': 3.30033802986145, 'mean': 0.11401384323835373, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} tflite={'shape': [1, 8400, 48], 'dtype': 'float32', 'min': -1.0704050064086914, 'max': 3.300187826156616, 'mean': 0.11402502655982971, 'nan_count': 0, 'inf_count': 0, 'finite_count': 403200} mean_abs_diff=4.61839881609194e-05 max_abs_diff=0.002176523208618164
