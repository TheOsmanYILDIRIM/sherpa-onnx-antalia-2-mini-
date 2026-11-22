"""Small Chaquopy bridge around the upstream Clarvia Turkish phonemizer."""
import json
import re
import clarvia_phonemizer

_SENTENCE_END = re.compile(r'(?<=[.!?…])\s+(?=["\'(\[]?[A-ZÇĞİÖŞÜ0-9])')


def _sentences(text):
    text = re.sub(r"\s+", " ", str(text)).strip()
    if text and not re.search(r'[.!?…,;:]["\'”’)\]»]*$', text):
        text += "."
    return [part for part in _SENTENCE_END.split(text) if re.search(r"\w", part)]


def phonemize_chunks_json(text, max_tokens=500):
    pending = _sentences(text)
    output = []
    while pending:
        sentence = pending.pop(0)
        try:
            phones = clarvia_phonemizer.phonemize(sentence)
        except ValueError:
            continue
        ids = [int(x) for x in phones.ids]
        stress = [int(x) for x in phones.stress]
        if len(ids) > max_tokens:
            parts = re.split(r"(?<=[,;:])\s+", sentence)
            if len(parts) == 1:
                words = sentence.split()
                if len(words) <= 1:
                    raise ValueError("Clarvia sentence exceeds the 512-token model limit")
                middle = len(words) // 2
                parts = [" ".join(words[:middle]), " ".join(words[middle:])]
            pending = [part for part in parts if part.strip()] + pending
            continue
        output.append({"ids": ids, "stress": stress})
    if not output:
        raise ValueError("nothing in the text could be read")
    return json.dumps(output, separators=(",", ":"))
