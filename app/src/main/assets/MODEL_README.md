# Frigate Android AI Models

Download the SSD MobileNet V2 TFLite model for object detection:

```bash
# Navigate to the repository root
cd ~/Documents/frigate-android

# Download SSD MobileNet V2 (quantized, ~7MB - optimal for mobile NPU/GPU)
wget -O app/src/main/assets/ssd_mobilenet_v2.tflite \
  https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite/model_edgetpu.tflite

# Alternative: EfficientDet-Lite0 (better accuracy, slightly larger)
# wget -O app/src/main/assets/ssd_mobilenet_v2.tflite \
#   https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite
```

The model should be placed at: `app/src/main/assets/ssd_mobilenet_v2.tflite`

### Recommended models:
1. **SSD MobileNet V2** (default) - 7MB, 30-60 fps on mobile GPU/NPU, Frigate's recommended model
2. **EfficientDet-Lite0** - 14MB, better accuracy, 20-40 fps
3. **YOLOv8-tiny** - Custom, requires conversion to TFLite, best accuracy
