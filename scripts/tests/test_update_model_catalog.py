import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_model_catalog import build_snapshot, norm


class ModelCatalogSnapshotTest(unittest.TestCase):
    def test_normalization(self):
        for model_id in ("codex/GPT-6.1-sol", "GPT_6_1_SOL:free@eu"):
            self.assertEqual("gpt-6-1-sol", norm(model_id))

    def test_alias_conflicts_and_canonical_collisions_are_excluded(self):
        capability = {
            "tool_call": True,
            "reasoning": False,
            "modalities": {"input": ["text", "pdf"], "output": ["text"]},
            "limit": {"context": 100000},
        }
        models = {"lab/model.1": capability, "lab/model-2": capability}
        providers = {
            "one": {"models": {
                "model_1": {"canonical_model_id": "lab/model-2"},
                "shared": {"canonical_model_id": "lab/model.1"},
                "gateway/latest": {"canonical_model_id": "lab/model-2"},
                "missing": {"canonical_model_id": "absent"},
            }},
            "two": {"models": {
                "shared": {"canonical_model_id": "lab/model-2"},
                "latest": {"canonical_model_id": "lab/model-2"},
            }},
        }
        snapshot = build_snapshot(models, providers)
        self.assertEqual({"latest": "lab/model-2"}, snapshot["aliases"])
        self.assertEqual({"tool_call", "reasoning", "modalities"}, set(snapshot["models"]["lab/model.1"]))
        self.assertEqual(["text", "pdf"], snapshot["models"]["lab/model.1"]["modalities"]["input"])

    def test_malformed_model_entries_are_skipped(self):
        models = {
            "missing": {},
            "bad": {"tool_call": "true", "reasoning": False, "modalities": {"input": [], "output": []}},
        }
        self.assertEqual({}, build_snapshot(models, {})["models"])


if __name__ == "__main__":
    unittest.main()
