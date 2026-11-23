"""Build attributed Monolog word lists and romanization tables from the public archives."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import tarfile
import unicodedata
from urllib.request import Request, urlopen


FLORES = "https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz"
DAKSHINA = "https://storage.googleapis.com/gresearch/dakshina/dakshina_dataset_v1.0.tar"
DAKSHINA_SIZE = 2008340480
FLORES_CODES = {
    "en": "eng_Latn", "as": "asm_Beng", "bn": "ben_Beng", "brx": "brx_Deva",
    "doi": "doi_Deva", "gu": "guj_Gujr", "hi": "hin_Deva", "kn": "kan_Knda",
    "ks": "kas_Arab", "kok": "gom_Deva", "mai": "mai_Deva", "ml": "mal_Mlym",
    "mni-Beng": "mni_Beng", "mr": "mar_Deva", "ne": "npi_Deva", "or": "ory_Orya",
    "pa": "pan_Guru", "sa": "san_Deva", "sat": "sat_Olck", "sd": "snd_Arab",
    "ta": "tam_Taml", "te": "tel_Telu", "ur": "urd_Arab",
}
DAKSHINA_CODES = {"bn", "gu", "hi", "kn", "ml", "mr", "pa", "sd", "ta", "te", "ur"}
HEADER = "# Modified data · CC BY-SA 4.0 · See Indic-typing-data.txt\n"


def fetch(url, offset=None, size=None):
    headers = {"User-Agent": "Monolog typing data builder", "Accept-Encoding": "identity"}
    if offset is not None:
        headers["Range"] = f"bytes={offset}-{offset + size - 1}"
    with urlopen(Request(url, headers=headers), timeout=50) as response:
        if offset is not None and response.headers.get("Content-Range") != (
            f"bytes {offset}-{offset + size - 1}/{DAKSHINA_SIZE}"
        ):
            raise ValueError("The server did not serve the requested archive range.")
        return response.read()


def words(line):
    normalized = unicodedata.normalize("NFC", line.lower()).replace("’", "'")
    token = []
    for char in normalized + " ":
        if unicodedata.category(char)[0] in "LM" or char in "'\u200c\u200d":
            token.append(char)
        elif token:
            word = "".join(token).strip("'\u200c\u200d")
            if 1 <= len(word) <= 24 and word[0].isalpha():
                yield word
            token.clear()


def ranked(counter, cap, floor):
    return sorted(
        ((key, value) for key, value in counter.items() if value >= floor),
        key=lambda pair: (-pair[1], pair[0]),
    )[:cap]


def flores(cache, output):
    archive = cache / "flores200_dataset.tar.gz"
    if not archive.exists():
        archive.write_bytes(fetch(FLORES))
    results = {}
    with tarfile.open(archive) as tar:
        paths = {Path(member.name).name: member for member in tar if member.isfile()}
        for code, tag in FLORES_CODES.items():
            counts, pairs = Counter(), Counter()
            for split in ("dev", "devtest"):
                member = paths.get(f"{tag}.{split}")
                if member is None:
                    continue
                for line in tar.extractfile(member):
                    tokens = list(words(line.decode("utf-8")))
                    counts.update(tokens)
                    pairs.update(zip(tokens, tokens[1:]))
            if not counts:
                print("FLORES has no public archive rows for", code, flush=True)
                continue
            frequent = dict(ranked(counts, 2800, 2))
            pairs = Counter({key: value for key, value in pairs.items() if all(w in frequent for w in key)})
            lines = [f"{word}\t{count}\n" for word, count in frequent.items()]
            lines += [f"{first}\t{second}\t{count}\n" for (first, second), count in ranked(pairs, 1400, 3)]
            (output / f"flores-{code}.tsv").write_text(HEADER + "".join(lines), encoding="utf-8")
            results[code] = frequent
    return results


def dakshina_members(cache):
    # The official distribution is an uncompressed tar. Read the headers and
    # selected lexicons without saving its 2 GB Wikipedia payload to disk.
    index = cache / "dakshina-ranges.tsv"
    if index.exists():
        return [(key, int(offset), int(size)) for key, offset, size in (
            line.split("\t") for line in index.read_text().splitlines()
        )]
    offset = 0
    window, start = b"", 0
    selected = []
    while offset + 512 <= DAKSHINA_SIZE:
        if not start <= offset <= start + len(window) - 512:
            start = offset
            window = fetch(DAKSHINA, start, min(16384, DAKSHINA_SIZE - start))
        header = window[offset - start : offset - start + 512]
        if header == bytes(512):
            break
        member = tarfile.TarInfo.frombuf(header, "utf-8", "strict")
        match = re.fullmatch(r"dakshina_dataset_v1\.0/([a-z]{2})/lexicons/\1\.translit\.sampled\.(?:train|dev|test)\.tsv", member.name)
        if match and match[1] in DAKSHINA_CODES:
            selected.append((match[1], offset + 512, member.size))
        offset += 512 + ((member.size + 511) // 512) * 512
    if {code for code, _, _ in selected} != DAKSHINA_CODES:
        raise ValueError("The official archive did not contain all expected lexicons.")
    index.write_text("".join(f"{code}\t{offset}\t{size}\n" for code, offset, size in selected))
    return selected


def dakshina(cache, output, frequencies):
    def entry(metadata):
        code, offset, size = metadata
        file = cache / f"dakshina-{code}-{offset}.tsv"
        if not file.exists():
            file.write_bytes(fetch(DAKSHINA, offset, size))
        return code, file.read_text(encoding="utf-8")

    counts = {code: Counter() for code in DAKSHINA_CODES}
    with ThreadPoolExecutor(max_workers=8) as pool:
        for code, part in pool.map(entry, dakshina_members(cache)):
            for line in part.splitlines():
                native, latin, votes = line.split("\t")
                native = unicodedata.normalize("NFC", native)
                if re.fullmatch(r"[a-z]{1,24}", latin) and next(words(native), None) == native:
                    counts[code][latin, native] += int(votes)
    for code, votes in counts.items():
        freq = frequencies.get(code, {})
        # Prefer attested spellings of words the parallel corpus actually uses.
        order = sorted(votes, key=lambda pair: (-min(freq.get(pair[1], 0), 72), -votes[pair], pair))
        selected = sorted(order[:6000])
        lines = [f"{latin}\t{native}\t{votes[latin, native]}\n" for latin, native in selected]
        (output / f"dakshina-{code}.tsv").write_text(HEADER + "".join(lines), encoding="utf-8")
        print(f"Dakshina {code}: {len(lines)} attested spellings", flush=True)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=root / "app/build/typing-data-sources")
    options = parser.parse_args()
    output = root / "app/src/main/assets/typing/indic"
    output.mkdir(parents=True, exist_ok=True)
    options.cache.mkdir(parents=True, exist_ok=True)
    frequencies = flores(options.cache, output)
    dakshina(options.cache, output, frequencies)


if __name__ == "__main__":
    main()
