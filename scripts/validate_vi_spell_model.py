import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sentencepiece as spm


directory = Path(sys.argv[1]).resolve()
directory.mkdir(parents=True, exist_ok=True)
source = Path("talkback/src/main/java/com/google/android/accessibility/talkback/vietnameseime/VietnameseSpellModels.kt").read_text()
revision = re.search(r'const val REVISION = "([0-9a-f]{40})"', source).group(1)
specs = re.findall(r'Spec\("([^"]+)", ([\d_]+), "([0-9a-f]{64})"\)', source)
assert len(specs) == 5, "The application model catalog must contain five required files"
report = {"revision": revision, "files": []}
for name, raw_size, expected_hash in specs:
    size = int(raw_size.replace("_", ""))
    target = directory / name
    url = f"https://huggingface.co/nrl-ai/vn-spell-correction-small-onnx-int8/resolve/{revision}/{name}"
    digest = hashlib.sha256()
    received = 0
    with urllib.request.urlopen(url, timeout=60) as response, target.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            digest.update(chunk)
            received += len(chunk)
    assert received == size, f"Wrong size for {name}: {received} != {size}"
    assert digest.hexdigest() == expected_hash, f"Wrong SHA-256 for {name}"
    print(f"Verified {name}: {received} bytes", flush=True)
    report["files"].append({"name": name, "bytes": received, "sha256": digest.hexdigest()})

processor = spm.SentencePieceProcessor(model_file=str(directory / "sentencepiece.bpe.model"))
vocabulary = {"<s>": 0, "<pad>": 1, "</s>": 2, "<unk>": 3}
for line in (directory / "dict.txt").read_text().splitlines():
    vocabulary[line.split()[0]] = len(vocabulary)
vocabulary["<mask>"] = len(vocabulary)
inverse = {value: key for key, value in vocabulary.items()}
config = json.loads((directory / "config.json").read_text())
assert config["vocab_size"] == len(vocabulary) == 40030
assert config["bos_token_id"] == 0 and config["pad_token_id"] == 1
assert config["eos_token_id"] == config["decoder_start_token_id"] == 2


def encode(text):
    return [0] + [vocabulary.get(piece, 3) for piece in processor.encode(text, out_type=str)] + [2]


def decode(ids):
    return "".join(inverse[token] for token in ids if 3 < token < 40029).replace("▁", " ").strip()


texts = [
    "Tôi yêu Việt Nam, đất nước tuyệt vời.",
    "Toi yeu Viet Nam, dat nuoc tuyet voi",
    "Xin chào! Giá: 15.000 đồng; email a@example.com.",
    "  nhiều   khoảng trắng  ",
    "Dấu tổ hợp: Tôi yêu Việt Nam.",
    "Hàng một\tHàng hai\nHàng ba",
    "🙂🙂 👨‍👩‍👧‍👦 中文 한국어",
    "Ｋｈｏ̂ｎｇ　ｇｉａｎ ① ½ \u0001",
    "",
]
cases = [{"text": text, "ids": encode(text), "decoded": decode(encode(text))} for text in texts]
fixture = {"directory": str(directory), "cases": cases}
(directory / "fixture.json").write_text(json.dumps(fixture, ensure_ascii=False))

options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
options.log_severity_level = 3
encoder = ort.InferenceSession(str(directory / "encoder_model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
decoder = ort.InferenceSession(str(directory / "decoder_model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
encoder_names = {value.name for value in encoder.get_inputs()}
decoder_names = {value.name for value in decoder.get_inputs()}
assert encoder_names == {"input_ids", "attention_mask"}, encoder_names
assert decoder_names == {"input_ids", "encoder_hidden_states", "encoder_attention_mask"}, decoder_names
assert decoder.get_outputs()[0].name == "logits"
report["encoder_inputs"] = sorted(encoder_names)
report["decoder_inputs"] = sorted(decoder_names)
ids = np.array([encode(texts[1])], dtype=np.int64)
mask = np.ones_like(ids)
hidden = encoder.run(None, {"input_ids": ids, "attention_mask": mask})[0]
generated = [2]
for _ in range(128):
    logits = decoder.run(["logits"], {
        "input_ids": np.array([generated], dtype=np.int64),
        "encoder_hidden_states": hidden,
        "encoder_attention_mask": mask,
    })[0]
    token = int(np.argmax(logits[0, -1]))
    if token == 2:
        break
    generated.append(token)
else:
    raise AssertionError("The model failed to finish a short correction")
corrected = decode(generated)
assert corrected and any(ord(character) > 127 for character in corrected), corrected
report["sample_input"] = texts[1]
report["sample_output"] = corrected
report["tokenizer_reference_cases"] = len(cases)
(directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
