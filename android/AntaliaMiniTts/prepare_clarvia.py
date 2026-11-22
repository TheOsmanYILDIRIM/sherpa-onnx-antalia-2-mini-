#!/usr/bin/env python3
"""Resolve and stage Clarvia Tiny ONNX + its exact phonemizer sources for Android."""
from __future__ import annotations
import hashlib
import json
import shutil
import sys
from pathlib import Path
from huggingface_hub import HfApi, hf_hub_download, snapshot_download

MODEL_REPO = "wite-tech/clarvia-tts-tiny"
PHONEMIZER_REPO = "wite-tech/clarvia-phonemizer"
PUBLIC_VOICES = [
    "male_1", "male_2", "female_1", "female_2", "female_3",
    "female_4", "female_5", "male_3", "male_4", "male_5",
]

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def main(asset_dir: Path, python_dir: Path) -> None:
    api = HfApi()
    model_revision = api.model_info(MODEL_REPO).sha
    phonemizer_revision = api.model_info(PHONEMIZER_REPO).sha
    if not model_revision or not phonemizer_revision:
        raise RuntimeError("Could not resolve immutable Clarvia revisions")

    asset_dir.mkdir(parents=True, exist_ok=True)
    python_dir.mkdir(parents=True, exist_ok=True)

    model = Path(hf_hub_download(MODEL_REPO, "onnx/model.onnx", revision=model_revision))
    config = Path(hf_hub_download(MODEL_REPO, "config.json", revision=model_revision))
    license_file = Path(hf_hub_download(MODEL_REPO, "LICENSE", revision=model_revision))
    shutil.copyfile(model, asset_dir / "model.onnx")
    shutil.copyfile(config, asset_dir / "config.json")
    shutil.copyfile(license_file, asset_dir / "LICENSE")

    cfg = json.loads(config.read_text(encoding="utf-8"))
    assert cfg["sample_rate"] == 24000
    assert cfg["public_voices"] == PUBLIC_VOICES
    assert cfg["inputs"] == ["tokens", "stress", "voice", "speed", "pitch_shift", "pitch_range"]

    snapshot = Path(snapshot_download(
        PHONEMIZER_REPO,
        revision=phonemizer_revision,
        allow_patterns=["clarvia_phonemizer/**", "LICENSE"],
    ))
    source_pkg = snapshot / "clarvia_phonemizer"
    target_pkg = python_dir / "clarvia_phonemizer"
    if target_pkg.exists():
        shutil.rmtree(target_pkg)
    shutil.copytree(source_pkg, target_pkg)
    shutil.copyfile(snapshot / "LICENSE", asset_dir / "PHONEMIZER_LICENSE")

    manifest = {
        "model": MODEL_REPO,
        "model_revision": model_revision,
        "phonemizer": PHONEMIZER_REPO,
        "phonemizer_revision": phonemizer_revision,
        "sample_rate": 24000,
        "voices": PUBLIC_VOICES,
        "model_sha256": sha256(asset_dir / "model.onnx"),
        "config_sha256": sha256(asset_dir / "config.json"),
    }
    (asset_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: prepare_clarvia.py ASSET_DIR PYTHON_SOURCE_DIR")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
