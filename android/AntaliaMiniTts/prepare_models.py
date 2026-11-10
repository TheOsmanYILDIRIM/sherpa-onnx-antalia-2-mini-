#!/usr/bin/env python3
"""Prepare verified original 4-stage Antalia Mini ONNX graphs for Android.

SHA-256 values were read from the user-provided original 0.1.1 ARM64 APK.
Never silently fall back to a different revision/graph.
"""
from __future__ import annotations
import hashlib
import json
import shutil
import sys
from pathlib import Path
from urllib.request import urlopen
import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download

HF_REPO = 'cloud0day3/antalia-mini'
HF_REV = '2c24b406fedada11bd731a2ec9f4535bfd354f03'
NORMALIZER_REV = '459585b3bf67db5db5d9ec03d394d9b2c95412f2'
EXPECTED = {
    'text.onnx': '6b74570a31734f9aebb121dce04a44f09c2aa0fbe0d299147c4506b4546b2ce8',
    'cond.onnx': '55aa32749a8a6f8180f06694e9b36a10b2c632d8ecd250e04be6823bfc2b15ec',
    'flow.onnx': '0ec1fca11bad905e8fba85c2855ae08042a0787e244d7cbc525059349ffc16a5',
    'vocoder.fp16.onnx': 'c82c830e4fc83446918af9f4f35711e4b3c361eab3a25288d117b7ff4cb24417',
}
NORMALIZER_ASSETS = (
    'vocab.json', 'config.json', 'foreign_dict.json', 'foreign_parts.json',
    'known_words.json', 'english_i.json', 'LICENSE', 'NOTICE',
)

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def prepare(source: Path, dest: Path) -> None:
    meta_path = source / 'provenance.json'
    meta = json.loads(meta_path.read_text(encoding='utf-8'))
    assert meta['version'] == '1.0.1'
    assert meta['model_revision'] == '0965f4a5fb578763be8ca2a2bb39421378471cd1'
    dest.mkdir(parents=True, exist_ok=True)
    for name, digest in EXPECTED.items():
        path = Path(hf_hub_download(HF_REPO, filename='models/' + name,
                                    repo_type='space', revision=HF_REV))
        assert sha256(path) == digest, f'Original APK checksum mismatch: {name}'
        shutil.copyfile(path, dest / name)
        assert sha256(dest / name) == digest
        print('Verified:', name, path.stat().st_size)
    for name in NORMALIZER_ASSETS:
        path = source / name
        assert path.is_file() and sha256(path) == meta['sha256'][name], name
        shutil.copyfile(path, dest / name)
    normalizer_url = ('https://raw.githubusercontent.com/fr0stb1rd/antalia-mini-web/'
                      + NORMALIZER_REV + '/normalize.js')
    with urlopen(normalizer_url, timeout=45) as response:
        js = response.read()
    assert b'AntaliaText' in js and len(js) > 20000
    (dest / 'normalize.js').write_bytes(js)
    manifest = {
        'model': 'Antalia-2 Mini', 'model_version': '1.0.1',
        'space_repo': HF_REPO, 'space_revision': HF_REV,
        'precision': 'FP32 text/cond/flow + FP16 vocoder',
        'sha256': EXPECTED,
        'normalizer_commit': NORMALIZER_REV,
        'normalizer_sha256': sha256(dest / 'normalize.js'),
        'source_provenance_sha256': sha256(meta_path),
        'real_device_quality_test_pending': True,
    }
    (dest / 'graph_manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    verify_graphs(dest)

def verify_graphs(dest: Path) -> None:
    expected = {
        'text.onnx': (('ids',), ('h', 'logd')),
        'cond.onnx': (('h', 'idx'), ('ctok', 'cnull')),
        'flow.onnx': (('x', 'ctok', 'cnull', 't', 'dt', 'cfg'), ('x_next',)),
        'vocoder.fp16.onnx': (('z',), ('mel', 're', 'im')),
    }
    sessions = {}
    for name, (inputs, outputs) in expected.items():
        session = ort.InferenceSession(str(dest / name), providers=['CPUExecutionProvider'])
        assert tuple(x.name for x in session.get_inputs()) == inputs, name
        assert tuple(x.name for x in session.get_outputs()) == outputs, name
        sessions[name] = session
    ids = np.array([[1, 12, 13, 14, 3, 15, 16, 2]], dtype=np.int64)
    h, logd = sessions['text.onnx'].run(None, {'ids': ids})
    assert h.shape == (1, 8, 192) and logd.shape == (1, 8)
    idx = np.minimum(np.arange(32) // 4, 7).astype(np.int64).reshape((1, 32))
    ctok, cnull = sessions['cond.onnx'].run(None, {'h': h, 'idx': idx})
    assert ctok.shape == cnull.shape == (1, 16, 192), (ctok.shape, cnull.shape)
    x = np.random.default_rng(123).normal(0, .6, (1, 16, 256)).astype(np.float32)
    for step in range(4):
        x = sessions['flow.onnx'].run(None, {
            'x': x, 'ctok': ctok, 'cnull': cnull,
            't': np.array([step * .25], dtype=np.float32),
            'dt': np.array([.25], dtype=np.float32),
            'cfg': np.array([2.], dtype=np.float32),
        })[0]
        assert x.shape == (1, 16, 256) and np.isfinite(x).all()
    mel, re, im = sessions['vocoder.fp16.onnx'].run(None, {'z': x})
    assert mel.shape == (1, 128, 32)
    assert re.shape == im.shape == (1, 1025, 32), (re.shape, im.shape)
    assert np.isfinite(re).all() and np.isfinite(im).all()
    print('Verified split ONNX graph interfaces and four Euler steps.')

if __name__ == '__main__':
    assert len(sys.argv) == 3, 'usage: prepare_models.py SRC_ARTIFACT DEST_ANDROID_ASSETS'
    prepare(Path(sys.argv[1]), Path(sys.argv[2]))
