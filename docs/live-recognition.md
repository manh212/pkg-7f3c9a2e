# Offline live recognition

The five camera modes are available in Live recognition and its existing reading control. Each mode can be enabled independently. The camera stops when the screen closes or the screen reader pauses.

| Mode | Implementation | Coverage and limits |
| --- | --- | --- |
| QR and barcode | ZXing Core with centered and full-frame decoding | Reads common 1D and 2D formats. Formats VietQR, Wi-Fi, ID, and link payloads for speech. Checks the VietQR checksum. GS1 893 identifies the allocating organization. |
| Color | Center-region RGB median with highlight and shadow trimming and perceptual color names | Reports a stable color after two samples. White balance and illumination still affect the result. |
| Lottery ticket | Bundled PaddleOCR text detector and Vietnamese Tesseract recognition, followed by ticket-specific parsing | Reads supported traditional and Vietlott formats after two matching frames. Mega 6/45, Power 6/55, and Max 3D are recognized offline. Optional traditional lookup uses the station's RSS feed and the full draw date. Only a valid published-number match is announced as a candidate. A missing match remains unverified. |
| Find object | MediaPipe EfficientDet-Lite0 INT8 (320 px) with everyday COCO classes and Vietnamese labels | Filters the selected class and speaks left, center or right. Object distance and objects absent from the supported COCO list require a different model or input modality. |
| Document and label | OpenCV alignment, perspective correction and the same bundled Vietnamese OCR | Guides framing, reads a stable document, and falls back to full-frame OCR for a sharp label without detectable edges. Speaks recognized expiry and manufacturing date strings. |

The optional light tone maps ambient sensor readings to pitch and pauses while the screen reader speaks. Currency total counts one note after two stable frames. Clear the view for at least three analyzed frames before presenting the next note. Use the reset button to start a new total.

## Models and distribution

EfficientDet-Lite0 INT8 is the default build asset. Its model card identifies the model as Apache-2.0. Google reports an average CPU latency of 29.31 ms on Pixel 6 for the MediaPipe task. This figure is a reference benchmark. Accuracy and latency on Meridian devices still need device measurements.

- [EfficientDet-Lite0 model card and hashes](https://huggingface.co/litert-community/MediaPipe-ObjectDetector-EfficientDet-Lite0)
- [Google Object Detector benchmarks](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector#task-benchmarks)
- [PaddleOCR model card](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [Tesseract Vietnamese language data](https://github.com/tesseract-ocr/tessdata_fast/blob/4.1.0/vie.traineddata)
- [RSS station directory](https://xskt.com.vn/rss)
- [GS1 barcode prefix guidance](https://support.gs1.org/support/solutions/articles/43000734188)

The APK ships the Vietnamese OCR detector and language data in `talkback/src/main/assets/offline-ocr-v1/`. The modes install these assets on first use with a size and SHA-256 check. The bundled assets also serve the existing OCR settings. An existing valid OCR installation is reused.

An independently licensed Ultralytics build can use `talkback/src/main/assets/coco_yolo11n.tflite`. The supported profile is a YOLO11n COCO export with normalized box coordinates, float32 output and no embedded NMS. The default Apache-licensed build ships EfficientDet-Lite0. Ultralytics publishes YOLO11 weights under AGPL-3.0 and offers commercial licensing.

```bash
yolo export model=yolo11n.pt format=tflite imgsz=640
cp yolo11n_saved_model/yolo11n_float32.tflite talkback/src/main/assets/coco_yolo11n.tflite
```

YOLO26n shows higher COCO mAP than YOLO11n in Ultralytics' benchmarks. Its default end-to-end output layout differs from the YOLO11n decoder. For QR, color and Vietnamese OCR, compare field accuracy on representative camera images before replacing the existing engines.

- [Ultralytics YOLO11 license](https://huggingface.co/Ultralytics/YOLO11)
- [YOLO26 versus YOLO11 benchmarks](https://docs.ultralytics.com/compare/yolo26-vs-yolo11)

## Device checks

Use the published dev APK on an arm64 Android phone. Check Vietnamese focus order, simultaneous camera modes, OCR on first launch in airplane mode, exposure with and without the torch, and that leaving the screen stops the camera and light tone. Compare known QR codes, printed labels, traditional and Vietlott tickets, and common objects in multiple positions and lighting conditions. Ensure an unrecognized or outdated lottery result is never spoken as a confirmed loss.
