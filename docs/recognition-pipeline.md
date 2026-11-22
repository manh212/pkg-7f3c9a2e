# Camera recognition and model delivery

Reviewed on 2026-11-22 after a user report of inaccurate live text recognition.

## Diagnostic evidence

The supplied diagnostic excerpt records the TEXT camera mode running. OCR returned text repeatedly, some results were suppressed by the announcement interval, and others waited for existing speech. No native crash was recorded. The excerpt contains no source photographs, reference transcription, per-line confidence or camera frame dimensions. It cannot establish an OCR error rate or separate model limitations from focus, lighting, framing and preprocessing errors.

The previous camera pipeline read each OCR result after one recognition. It used the Vietnamese Tesseract recognizer shared with screenshots, with contrast enhancement and sharpening before detection. Switching between `fast` and `best` did not establish a measured improvement on this user's photographs.

## Current pipeline

| Task | Implementation |
| --- | --- |
| Camera text, documents and lottery tickets | PP-OCRv6 small text detector plus VietOCR `vgg_seq2seq`, using ONNX Runtime inside the separate `:ocr` process. |
| Screenshot OCR and screen coordinates | Existing Vietnamese Tesseract LSTM and PP-OCR detector, with existing word-coordinate mapping. |
| QR and barcode decoding | ZXing-C++ Android, with Java ZXing fallback. No neural model is needed to decode QR codes. Numeric identity-barcode OCR is acquired only when needed, or reused when already installed. |
| Document framing | OpenCV geometry and perspective correction, with no page-segmentation model. Curved pages are not flattened. |
| Currency | Verified MoneyVision TensorFlow Lite model, downloaded on first use. |
| Object finding | EfficientDet-Lite0, downloaded on first use. |

VietOCR receives RGB line crops scaled to height 32 and width 32–512, with values in 0–1. Encoder hidden state and features feed the autoregressive decoder. Unknown tokens, non-finite scores and decoding without an end token are rejected. The geometric mean of character probabilities is a quality gate, not a calibrated claim of accuracy. Lines below 0.75 are omitted.

Live text and document speech require two consistent recognitions. Short text must match exactly. Longer text permits small word differences while requiring all digits to agree. Empty results, stale samples and new sessions reset confirmation. Low-light and blurry text frames receive guidance instead of OCR speech. Pending text is cleared when a newer frame is not confirmed. Lottery parsing retains its separate repeated-ticket confirmation and conservative number/date rules.

Camera recognition uses original colour data and perspective correction without the previous unconditional contrast/sharpening pass. Main-process diagnostics record camera dimensions, accepted line count, average confidence, processing time and confirmation state without adding raw OCR text to these metrics.

## Downloads

No recognition model weights are packaged in application assets. Notices remain in the APK. Each feature downloads only its required model group, validates the size and SHA-256 through the shared downloader, and reuses installed files. Partial files use a separate name and can resume on the next attempt. Existing installed screenshot OCR files remain usable.

| Group | Downloaded files | Approximate first download |
| --- | --- | --- |
| Camera OCR | Shared detector, VietOCR encoder, decoder and vocabulary | 99.3 MB; about 89.5 MB if the detector is already installed |
| Screenshot OCR | Shared detector and Vietnamese Tesseract data | 22.3 MB |
| Currency | MoneyVision model | 3.0 MB |
| Object finding | EfficientDet-Lite0 | 4.6 MB |

Camera modes remain selected while their models download, show progress and start automatically when ready. Already usable modes continue running. Download failure reports an error and allows retry by re-enabling the affected mode. Opening the recognition screen alone does not download models. Screenshot OCR starts its download when invoked; a screen-recognition request may need to be invoked again after downloading. Subtitle OCR starts downloading when enabled and resumes its scheduled capture when the files become available. Other existing AI and dictation downloads retain their separate model management; their weights are already outside the APK.

## Model choice and limitations

[VietOCR](https://github.com/pbcquoc/vietocr) specifically targets Vietnamese line recognition. The author provides both Transformer and attention Seq2Seq variants. Seq2Seq was chosen for the camera because its autoregressive architecture has an available ONNX conversion and the author's published comparison favours its speed over the Transformer variant. GPU timings published by the author do not establish Android CPU latency.

The [ONNX export](https://github.com/duchoa1297/vietocr-onnx/releases/tag/ocr-v1) publishes model hashes, vocabulary, preprocessing and decoder inputs. Its author's sample results are not this application's benchmark. The app retains the existing PP-OCR detector rather than downloading another detector just for VietOCR. Original VietOCR and the ONNX export are Apache-2.0 licensed.

[PaddleOCR's Latin recognizer](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md) also supports Vietnamese. Its aggregate multilingual metrics do not establish a win on these camera frames. [FairScan](https://github.com/pynicolas/FairScan) addresses page segmentation and is GPLv3; it is not incorporated.

Model sizes, URLs and SHA-256 values are in `OfflineModels.kt`. The original currency download URL returned 404 during review. Its verified copy is served from this repository at commit `9cdc3c5`, with the original MIT attribution retained. The detector is pinned to its verified Hugging Face revision. VietOCR uses the `ocr-v1` release plus SHA-256 validation. Actual accuracy and latency must be assessed on representative physical documents, ticket numbers and low-light camera frames. No source images or private diagnostic text are committed to this repository.
