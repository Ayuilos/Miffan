"""Checks and bulk writes for agent-driven localization.

The agent (or a model it delegates to) writes the translations; these helpers only validate them,
write them into every strings.xml at once, and report what is still untranslated.
"""

import re
from dataclasses import dataclass
from pathlib import Path

from lxml import etree

HAN = re.compile(r"[一-鿿]")
# Android format placeholders: %s, %d, %1$s, %2$d, %.1f, %%
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoc%]")
STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def looks_chinese(value: str) -> bool:
    """Mostly Chinese text. A Chinese brand name or example inside English text does not count."""
    return len(HAN.findall(value)) > len(re.findall(r"[A-Za-z]", value))


def escape_android(value: str) -> str:
    """Escapes apostrophes and double quotes that aapt would otherwise reject or strip."""
    value = re.sub(r"(?<!\\)'", r"\\'", value)
    return re.sub(r'(?<!\\)"', r'\\"', value)


def placeholders(value: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(value))


@dataclass
class Problem:
    key: str
    lang: str
    message: str

    def __str__(self) -> str:
        return f"{self.key} [{self.lang}]: {self.message}"


def validate(
    entries: dict[str, dict[str, str]],
    languages: list[str],
    existing_source: dict[str, str],
) -> list[Problem]:
    """Checks a batch of {key: {lang: value}} before it is written.

    Every key must end up with all languages, the English source must not contain Chinese, and each
    translation must keep the source's placeholders.
    """
    problems = []
    for key, values in entries.items():
        source = values.get("values", existing_source.get(key))
        if source is None:
            problems.append(Problem(key, "values", "no English source in the batch or the module"))
            continue
        if looks_chinese(source):
            problems.append(Problem(key, "values", "English source contains Chinese characters"))
        for lang in languages:
            value = values.get(lang)
            if value is None:
                if lang != "values" or key not in existing_source:
                    problems.append(Problem(key, lang, "missing"))
                continue
            if not value.strip():
                problems.append(Problem(key, lang, "empty"))
            if placeholders(value) != placeholders(source):
                problems.append(Problem(
                    key, lang, f"placeholders {placeholders(value)} differ from source {placeholders(source)}",
                ))
        for lang in values:
            if lang not in languages:
                problems.append(Problem(key, lang, "unknown language code"))
    return problems


def write_entries(file_path: Path, entries: dict[str, str]) -> None:
    """Adds or replaces several entries in one strings.xml, keeping the file's order and comments."""
    if file_path.exists():
        parser = etree.XMLParser(remove_blank_text=True)
        tree = etree.parse(str(file_path), parser)
        root = tree.getroot()
    else:
        file_path.parent.mkdir(parents=True, exist_ok=True)
        root = etree.Element("resources")
        tree = etree.ElementTree(root)
    existing = {e.get("name"): e for e in root.findall("string")}
    for key, value in entries.items():
        element = existing.get(key)
        if element is None:
            element = etree.SubElement(root, "string")
            element.set("name", key)
        element.text = value
    etree.indent(root, space="  ")
    tree.write(str(file_path), encoding="utf-8", xml_declaration=True, pretty_print=True)


def read_strings(file_path: Path) -> dict[str, str]:
    """Translatable plain strings of one strings.xml."""
    if not file_path.exists():
        return {}
    root = etree.parse(str(file_path)).getroot()
    return {
        e.get("name"): "".join(e.itertext())
        for e in root.findall("string")
        if e.get("translatable") != "false"
    }


def find_missing(res_dir: Path, languages: list[str]) -> dict[str, dict[str, str]]:
    """Keys needing work: {key: {"values": source, lang: reason, ...}}.

    Reports keys absent from a language, and English sources that still contain Chinese.
    """
    source = read_strings(res_dir / "values" / "strings.xml")
    targets = {lang: read_strings(res_dir / lang / "strings.xml") for lang in languages if lang != "values"}
    report: dict[str, dict[str, str]] = {}
    for key, value in source.items():
        issues = {lang: "missing" for lang, strings in targets.items() if key not in strings}
        if looks_chinese(value):
            issues["values"] = "English source contains Chinese"
        if issues:
            report[key] = {"source": value, **issues}
    return report


def scan_hardcoded(files: list[Path], root: Path) -> list[tuple[str, int, str]]:
    """String literals containing Chinese in source files, skipping comment lines.

    These are candidates for string resources; some (search keywords, provider names, prompts
    meant for Chinese models, debug pages) are legitimately Chinese and stay in code.
    """
    hits = []
    for file in files:
        in_block_comment = False
        for number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if in_block_comment:
                if "*/" in stripped:
                    in_block_comment = False
                continue
            if stripped.startswith("/*") and "*/" not in stripped:
                in_block_comment = True
                continue
            if stripped.startswith(("//", "*", "/*")):
                continue
            for literal in STRING_LITERAL.findall(line):
                if HAN.search(literal):
                    hits.append((str(file.relative_to(root)), number, literal))
    return hits
