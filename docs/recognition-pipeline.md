# Camera recognition decisions

Reviewed on 2026-11-22.

| Task | Implementation | Reason |
| --- | --- | --- |
| QR and barcode decoding | ZXing-C++ Android 3.1.1, Java ZXing fallback | Read multiple symbols, rotated and inverted codes locally. Prefer a structured citizen ID payload over generic symbols in the same image. |
| Citizen ID classification | Validated pipe-separated identity fields with or without the old-ID slot, or matching printed identity heading and number for a numeric barcode | A barcode symbology or an arbitrary twelve-digit value alone cannot identify a citizen ID card. |
| Document framing | OpenCV contours, convex quadrilateral validation and perspective correction | Suitable for flat pages and labels. Preserve aspect ratio and distinct corners on rotated pages. This does not flatten curved book pages. |
| Text and lottery OCR | PP-OCRv6 small ONNX detector and Vietnamese Tesseract LSTM `tessdata_best` 4.1.0 | Replace the smaller `fast` recognizer with the accuracy-oriented official model. Bundle the verified model and update existing installations by model size. Recognition can take longer. |
| Lottery interpretation | Conservative parsing after OCR, repeated-frame confirmation | Parse ticket numbers, draw dates and stations without inventing missing digits or choosing an ambiguous date. Online checking remains optional and limited to traditional tickets and the existing RSS feed. |

Camera text, document, ticket and QR modes request higher-detail frames when the device supports them. Object and colour modes keep their existing resolution when used alone. The OCR model is shared with screenshot OCR, so its speed/accuracy trade-off also applies there. OCR remains in the separate `:ocr` process and requires the existing OCR entitlement. Numeric barcode decoding still works when that entitlement is unavailable, but cannot use OCR to confirm a card.

## Alternatives reviewed

- [PaddleOCR multilingual recognition](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md) includes Vietnamese in the Latin recognizer. Its published aggregate metrics do not establish an improvement on this application's Vietnamese documents and lottery tickets. It would also replace line recognition and word-position mapping. No task-specific comparison is available yet, so this revision retains the existing detector/recognizer interface.
- [FairScan](https://github.com/pynicolas/FairScan) and its [segmentation model](https://github.com/pynicolas/fairscan-segmentation-model) provide Android document segmentation and perspective correction. Both are GPLv3. They are not incorporated into this Apache-licensed application.
- [AndroidScannerDemo](https://github.com/jhansireddy/AndroidScannerDemo) demonstrates the same OpenCV perspective-correction approach. Requiring visual manual corner adjustment would not suit this application's automatic spoken guidance.
- [Vietnam Lottery AI](https://github.com/sunbv56/Vietnam_Lottery_Checker_App) uses generative AI services. It does not provide evidence for a better dedicated offline lottery model for this Android pipeline.

## Sources and limitations

- [ZXing-C++ Android integration](https://github.com/zxing-cpp/zxing-cpp/tree/v3.1.1/wrappers/android)
- [Official identity-card fields from July 2024](https://mps.gov.vn/chinh-sach-phap-luat/bai-viet/ban-hanh-mau-the-can-cuoc-mau-giay-chung-nhan-can-cuoc-su-dung-tu-0172024-d1-t1415)
- [Citizen ID QR field example](https://github.com/Llein-Dev/vn-cccd-parser#2-parse-chip-cccd-qr-code)
- [Official Tesseract model comparison](https://tesseract-ocr.github.io/tessdoc/Data-Files.html)
- [Pinned Vietnamese model](https://github.com/tesseract-ocr/tessdata_best/blob/4.1.0/vie.traineddata)
- [Power 6/55 play options](https://vietlott.vn/vi/choi/power-6-55/cach-choi)

There is no supplied photograph or decoded payload from the reported card. Source review establishes weaknesses in first-result selection and classification, but cannot establish which code was decoded on that card. The parser tolerates additional fields and validates the name/date positions in both supported layouts. The official field list does not specify the serialized ordering; representative payloads from physical cards are still needed. The changes cover those weaknesses. Device photographs remain necessary to measure decoding rate, Vietnamese character accuracy and camera latency. The model author's comparative accuracy claim is not an application-specific benchmark.
