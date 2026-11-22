# Camera recognition and model delivery

Reviewed on 2026-11-22 after reports of inaccurate recognition and silent live text reading following the VietOCR switch.

## Diagnostic evidence

The supplied diagnostic excerpt records the TEXT camera mode running. OCR returned text repeatedly, some results were suppressed by the announcement interval, and others waited for existing speech. No native crash was recorded. The excerpt contains no source photographs, reference transcription, per-line confidence or camera frame dimensions. It cannot establish an OCR error rate or separate model limitations from focus, lighting, framing and preprocessing errors.

The previous camera pipeline read each OCR result after one recognition. It used the Vietnamese Tesseract recognizer shared with screenshots, with contrast enhancement and sharpening before detection. Switching between `fast` and `best` did not establish a measured improvement on this user's photographs.

## Current pipeline

| Task | Implementation |
| --- | --- |
| Camera text, documents and lottery tickets | Restored PP-OCRv6 small text detector plus Vietnamese Tesseract LSTM inside the separate `:ocr` process. VietOCR is not used by these modes. |
| Screenshot OCR and screen coordinates | Existing Vietnamese Tesseract LSTM and PP-OCR detector, with existing word-coordinate mapping. |
| QR and barcode decoding | ZXing-C++ Android, with Java ZXing fallback. No neural model is needed to decode QR codes. Numeric identity-barcode OCR is acquired only when needed, or reused when already installed. |
| Document framing | OpenCV geometry and perspective correction, with no page-segmentation model. Curved pages are not flattened. |
| Currency | Verified MoneyVision TensorFlow Lite model, downloaded on first use. |
| Object finding | EfficientDet-Lite0, downloaded on first use. |

The VietOCR integration could return no accepted lines after confidence filtering. Live text also required two near-identical recognitions, so a blank result or changing camera framing could keep showing a hold-still message indefinitely. The new report has no fresh diagnostic or source image to prove which condition occurred on the phone. Seeing the hold-still status follows model readiness and an OCR attempt; it is not the download screen.

Live text now reads the first non-empty Tesseract result. Image-quality guidance no longer blocks the OCR attempt; it is spoken when no readable text is returned. Empty results produce spoken guidance instead of a silent confirmation status. Long-stale results request another frame. Runtime failures are reported and stop the affected OCR modes. Document reading retains repeated-frame confirmation; lottery parsing retains its separate repeated-ticket confirmation and conservative number/date rules.

Camera recognition uses original colour data and perspective correction without the previous unconditional contrast/sharpening pass. Main-process diagnostics record camera dimensions, accepted line count, average confidence, processing time and confirmation state without adding raw OCR text to these metrics.

## Downloads

No recognition model weights are packaged in application assets. Notices remain in the APK. Each feature downloads only its required model group, validates the size and SHA-256 through the shared downloader, and reuses installed files. Partial files use a separate name and can resume on the next attempt. Existing installed screenshot OCR files remain usable.

| Group | Downloaded files | Approximate first download |
| --- | --- | --- |
| Camera OCR | Shared detector and Vietnamese Tesseract data | 22.3 MB; about 12.4 MB if the detector is already installed |
| Screenshot OCR | Shared detector and Vietnamese Tesseract data | 22.3 MB |
| Currency | MoneyVision model | 3.0 MB |
| Object finding | EfficientDet-Lite0 | 4.6 MB |

Camera modes remain selected while their models download, show progress and start automatically when ready. Download progress is spoken at quarter milestones and completion is announced. Camera and screenshot OCR reuse the same installed detector and Vietnamese data. Existing VietOCR files alone do not satisfy camera readiness, so affected installations download the missing Tesseract data before starting. Already usable modes continue running. Download failure reports an error and allows retry by re-enabling the affected mode. Opening the recognition screen alone does not download models. Screenshot OCR starts its download when invoked; a screen-recognition request may need to be invoked again after downloading. Subtitle OCR starts downloading when enabled and resumes its scheduled capture when the files become available. Other existing AI and dictation downloads retain their separate model management; their weights are already outside the APK.

## Model choice and limitations

[VietOCR](https://github.com/pbcquoc/vietocr) specifically targets Vietnamese line recognition. The author provides both Transformer and attention Seq2Seq variants. The previous camera integration used Seq2Seq, which has an available ONNX conversion. It is disabled following the silent-reading report, pending physical-device evaluation. GPU timings published by the author do not establish Android CPU latency.

The [ONNX export](https://github.com/duchoa1297/vietocr-onnx/releases/tag/ocr-v1) publishes model hashes, vocabulary, preprocessing and decoder inputs. Its author's sample results are not this application's benchmark. The app retains the existing PP-OCR detector and downloads no VietOCR weights for active camera modes. Original VietOCR and the ONNX export are Apache-2.0 licensed.

[PaddleOCR's Latin recognizer](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md) also supports Vietnamese. Its aggregate multilingual metrics do not establish a win on these camera frames. [FairScan](https://github.com/pynicolas/FairScan) addresses page segmentation and is GPLv3; it is not incorporated.

Model sizes, URLs and SHA-256 values are in `OfflineModels.kt`. The original currency download URL returned 404 during review. Its verified copy is served from this repository at commit `9cdc3c5`, with the original MIT attribution retained. The detector is pinned to its verified Hugging Face revision. The inactive VietOCR catalog retains its `ocr-v1` release and SHA-256 metadata for investigation; active camera modes use the Tesseract model. Actual accuracy and latency must be assessed on representative physical documents, ticket numbers and low-light camera frames. No source images or private diagnostic text are committed to this repository.
