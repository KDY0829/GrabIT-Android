# Existing YOLOX Android Report

## Model Loader
- Loader file: `app/src/main/java/com/example/grabit_test/HomeFragment.kt`
- Old model file: `yolox_nano_49cls_float16.tflite`
- New model file: `yolox_nano_retail_640_balanced_split_fp16.tflite`
- Interpreter: TensorFlow Lite `Interpreter`
- GPU: `GpuDelegate` attempted first, CPU fallback with 4 threads
- Model load helper: `loadModelFile(modelName: String)` maps an Android asset with `FileChannel.map`

## Existing Input
- Existing TFLite input shape: `[1, 640, 640, 3]`
- Existing TFLite input dtype: `float32`
- Android preprocessing:
  - Letterbox to model input size
  - `Bitmap.getPixels`
  - NHWC float buffer for `[1, 640, 640, 3]`
  - RGB channel order from Android pixel values: R, G, B
  - Values are raw `0..255` floats, no `0..1` normalization
  - NCHW path exists if an input tensor reports channel dimension at index 1

## Existing Output/Postprocess
- Existing TFLite output shape: `[1, 8400, 54]`
- Existing TFLite output dtype: `float32`
- Output tensor count: 1
- Android postprocess: `parseYOLOXOutput`
- Layout handling:
  - Row-major if `dim1 >= dim2`
  - Column-major fallback if output dims are transposed
- Box handling:
  - Supports normalized `xyxy` when `x2 > x1 && y2 > y1 && max <= 1.5`
  - Otherwise treats values as `cx, cy, w, h`
  - Uses letterbox scale when available
- Score handling:
  - Treats box sizes `85`, `58`, `55`, `54` as objectness + classes
  - Otherwise treats output as box + class scores without objectness
  - Applies sigmoid to class score values outside `[0, 1]`
- NMS:
  - IoU NMS threshold `0.6`
  - Additional same-label merge after NMS

## Labels
- Label file: `app/src/main/assets/classes.txt`
- Label count: 49
- Source comment: `instances_train_composited.json categories` order

## Compatibility Notes
- Old model: `[1, 8400, 54]`, interpreted as `4 box + 1 objectness + 49 classes`.
- New model: `[1, 8400, 48]`, interpreted by current code as `4 box + 44 classes`, no objectness.
- The new shape is structurally decodable by existing postprocess, but label/class-order compatibility is not proven because no 44-class order file or ONNX metadata exists in the repo.
