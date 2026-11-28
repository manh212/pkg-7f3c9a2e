# Offline Vietnamese spelling model

The spelling model settings download the pinned
`nrl-ai/vn-spell-correction-small-onnx-int8` revision
`c0cf880734f464515e4ad9c1ff47e5baeda4e708`. The earlier fallback requested
`encoder_model_quantized.onnx`, `decoder_model_quantized.onnx` and `tokenizer.json`,
which do not exist in that revision. The optional distribution manifest also did
not exist. The model uses BARTpho tokenization with a SentencePiece model and a
reduced Fairseq vocabulary; changing the two ONNX filenames alone is insufficient.

The application downloads five files: encoder, decoder without past-key caching,
SentencePiece model, dictionary and model configuration. This totals 199,676,068
bytes, approximately 191 MiB rounded up. The decoder-with-past graph is unused and
is not downloaded. File sizes and SHA-256 hashes come from the pinned repository
metadata or the corresponding downloaded small configuration/tokenizer files.

Downloads use the existing resumable, hash-verifying ModelDownloader. A completed
installation receipt is written only after every required file verifies. Existing
installations without that receipt are not considered ready. Cancellation retains
partial files for retry. Network, HTTP, integrity and storage failures have distinct
Vietnamese feedback. Returning to settings preserves the last failure state.

BartphoTokenizer reads the actual SentencePiece unigram scores and its precompiled
normalization map, chooses the highest-scoring segmentation and maps pieces through
the model's Vietnamese dictionary. Encoder tokens include BOS 0 and EOS 2; the
decoder starts at 2. Oversized input is rejected instead of silently dropping the
tail. A decoder that never produces EOS does not replace the original line.
Only the logits output is requested from the decoder because this implementation
does not consume the exported key/value cache outputs. Sessions are published
together after both graphs and the tokenizer load successfully.
An asynchronous correction is discarded if the editable field changes before
the result returns, so old model output cannot overwrite newer input.

For changes affecting the model contract, CI downloads the real pinned files,
verifies every size/hash, compares application token IDs and decoded text against
official SentencePiece output, checks both ONNX input schemas and performs a CPU
correction with ONNX Runtime. The `vi-spell-model-validation` artifact contains the
reference cases and result report, not model weights. Download cancellation,
integrity and HTTP failures also have unit coverage. These checks validate model
compatibility and functional inference; Android device latency and broad spelling
accuracy still require device measurements.

- [Pinned model](https://huggingface.co/nrl-ai/vn-spell-correction-small-onnx-int8/tree/c0cf880734f464515e4ad9c1ff47e5baeda4e708)
- [Transformers BARTpho tokenizer](https://github.com/huggingface/transformers/blob/v4.57.6/src/transformers/models/bartpho/tokenization_bartpho.py)
- [SentencePiece normalization](https://github.com/google/sentencepiece/blob/v0.2.1/src/normalizer.cc)
- [MediaPipe Bitmap ownership](https://github.com/google-ai-edge/mediapipe/blob/v0.10.35/mediapipe/java/com/google/mediapipe/framework/image/BitmapImageContainer.java)
