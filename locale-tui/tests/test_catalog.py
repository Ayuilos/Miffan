"""Tests for the agent-driven localization helpers. No API access needed.

Run with: uv run pytest tests/test_catalog.py -v
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from services import catalog

LANGS = ["values", "values-zh", "values-ja"]


def test_escape_android_escapes_quotes_once():
    assert catalog.escape_android("Don't") == "Don\\'t"
    assert catalog.escape_android("Don\\'t") == "Don\\'t"
    assert catalog.escape_android('Say "hi"') == 'Say \\"hi\\"'
    assert catalog.escape_android("Line one\nLine two") == "Line one\\nLine two"


def test_placeholders_ignore_a_plain_percent_sign():
    assert catalog.placeholders("10% off") == []
    assert catalog.placeholders("%1$s used %2$d%%, %.1f s") == ["%%", "%.1f", "%1$s", "%2$d"]


def test_validate_accepts_complete_entries_and_reports_problems():
    ok = {"greet": {"values": "Hi %1$s", "values-zh": "你好 %1$s", "values-ja": "こんにちは %1$s"}}
    assert catalog.validate(ok, LANGS, {}) == []

    bad = {
        "greet": {"values": "Hi %1$s", "values-zh": "你好", "values-fr": "Salut %1$s"},
        "chinese_source": {"values": "蓝色大肥鱼", "values-zh": "蓝色大肥鱼", "values-ja": "青い大きなデブ魚"},
    }
    messages = {str(p) for p in catalog.validate(bad, LANGS, {})}
    assert "greet [values-ja]: missing" in messages
    assert "greet [values-fr]: unknown language code" in messages
    assert any(m.startswith("greet [values-zh]: placeholders") for m in messages)
    assert "chinese_source [values]: English source contains Chinese characters" in messages


def test_validate_uses_existing_source_for_translation_only_updates():
    entries = {"greet": {"values-zh": "你好 %d"}}
    problems = catalog.validate(entries, LANGS, {"greet": "Hi %d"})
    assert [str(p) for p in problems] == ["greet [values-ja]: missing"]


def test_write_entries_updates_in_place_and_find_missing_reports_gaps(tmp_path):
    res = tmp_path / "res"
    catalog.write_entries(res / "values" / "strings.xml", {"a": "A", "b": "蓝鱼"})
    catalog.write_entries(res / "values-zh" / "strings.xml", {"a": "甲"})
    catalog.write_entries(res / "values-zh" / "strings.xml", {"a": "阿", "b": "蓝鱼"})

    assert catalog.read_strings(res / "values-zh" / "strings.xml") == {"a": "阿", "b": "蓝鱼"}
    report = catalog.find_missing(res, LANGS)
    assert report == {
        "a": {"source": "A", "values-ja": "missing"},
        "b": {"source": "蓝鱼", "values-ja": "missing", "values": "English source contains Chinese"},
    }


def test_scan_hardcoded_skips_comments(tmp_path):
    source = tmp_path / "Page.kt"
    source.write_text(
        '// Text("注释")\n'
        '/*\n Text("块注释")\n*/\n'
        'Text("蓝色大肥鱼") // 说明\n'
        'Text("English only")\n',
        encoding="utf-8",
    )
    assert catalog.scan_hardcoded([source], tmp_path) == [("Page.kt", 5, "蓝色大肥鱼")]
