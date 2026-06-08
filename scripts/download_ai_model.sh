#!/bin/bash
# Download AI model for Frigate Android
# Place this in project root and run from project root

set -e

MODEL_DIR="app/src/main/assets"
MODEL_FILE="$MODEL_DIR/ssd_mobilenet_v2.tflite"

echo "=== Frigate Android AI Model Download ==="

# Create assets directory if missing
mkdir -p "$MODEL_DIR"

if [ -f "$MODEL_FILE" ]; then
    echo "Model already exists: $MODEL_FILE ($(du -h "$MODEL_FILE" | cut -f1))"
    read -p "Re-download? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Skipping download."
        exit 0
    fi
fi

echo "Downloading SSD MobileNet V2 (quantized, ~7MB)..."

# Try multiple sources
URLS=(
    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite/model_edgetpu.tflite"
    "https://tfhub.dev/tensorflow/lite-model/ssd_mobilenet_v2/1/default/1?lite-format=tflite"
)

for url in "${URLS[@]}"; do
    echo "Trying: $url"
    if wget -q --show-progress -O "$MODEL_FILE" "$url" 2>/dev/null; then
        FILE_SIZE=$(stat -c%s "$MODEL_FILE" 2>/dev/null || stat -f%z "$MODEL_FILE" 2>/dev/null)
        if [ "$FILE_SIZE" -gt 100000 ]; then
            echo "✅ Model downloaded successfully: $MODEL_FILE ($(du -h "$MODEL_FILE" | cut -f1))"
            exit 0
        else
            echo "⚠️  Downloaded file too small ($FILE_SIZE bytes), trying next source..."
        fi
    else
        echo "  Failed, trying next source..."
    fi
done

echo ""
echo "❌ Could not download model automatically."
echo ""
echo "Manual download options:"
echo "  1. SSD MobileNet V2 (recommended):"
echo "     https://tfhub.dev/tensorflow/lite-model/ssd_mobilenet_v2/1/default/1"
echo "  2. EfficientDet-Lite0 (better accuracy):"
echo "     https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1"
echo ""
echo "Place the .tflite file at: $MODEL_FILE"
exit 1
