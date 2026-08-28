from __future__ import annotations

import json
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
CLASSES_PATH = REPO_ROOT / "app/src/main/assets/classes.txt"
DICTIONARY_PATH = REPO_ROOT / "app/src/main/assets/product_dictionary.json"
EXPECTED_COUNT = 43


def fail(message: str) -> None:
    raise SystemExit(f"validation failed: {message}")


def load_classes() -> list[str]:
    labels = CLASSES_PATH.read_text(encoding="utf-8").splitlines()
    labels = [label.strip() for label in labels]
    if any(not label for label in labels):
        fail("classes.txt contains blank lines")
    return labels


def load_dictionary() -> dict[str, Any]:
    try:
        data = json.loads(DICTIONARY_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f"product_dictionary.json parse error: {exc}")
    if not isinstance(data, dict):
        fail("product_dictionary.json root must be an object")
    return data


def main() -> None:
    labels = load_classes()
    dictionary = load_dictionary()

    if len(labels) != EXPECTED_COUNT:
        fail(f"classes.txt line count is {len(labels)}, expected {EXPECTED_COUNT}")
    if len(set(labels)) != len(labels):
        fail("classes.txt contains duplicate labels")
    if len(dictionary) != EXPECTED_COUNT:
        fail(f"dictionary key count is {len(dictionary)}, expected {EXPECTED_COUNT}")

    label_set = set(labels)
    key_set = set(dictionary)
    missing = [label for label in labels if label not in key_set]
    extra = sorted(key_set - label_set)
    if missing:
        fail(f"missing dictionary keys: {missing}")
    if extra:
        fail(f"extra dictionary keys: {extra}")

    for key in labels:
        entry = dictionary[key]
        if not isinstance(entry, dict):
            fail(f"{key}: entry must be an object")
        aliases = entry.get("aliases")
        tts_ko = entry.get("tts_ko")
        if not isinstance(aliases, list) or not aliases:
            fail(f"{key}: aliases must be a non-empty array")
        if not all(isinstance(alias, str) and alias.strip() for alias in aliases):
            fail(f"{key}: aliases must contain non-empty strings")
        if not isinstance(tts_ko, str) or not tts_ko.strip():
            fail(f"{key}: tts_ko must be a non-empty string")

    print(f"classes.txt line count: {len(labels)}")
    print(f"dictionary key count: {len(dictionary)}")
    print("missing dictionary keys: []")
    print("extra dictionary keys: []")
    print("JSON validation: ok")


if __name__ == "__main__":
    main()
