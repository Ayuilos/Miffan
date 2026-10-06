#!/usr/bin/env python3
"""Refresh the bundled models.dev capability snapshot (stdlib only).

Run manually before a release; this script is deliberately not part of Gradle.
Source data: https://github.com/anomalyco/models.dev (MIT).
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen


def norm(model_id):
    value = model_id.lower().rsplit("/", 1)[-1]
    return value.split(":", 1)[0].split("@", 1)[0].replace(".", "-").replace("_", "-")


def download(name):
    request = Request(f"https://models.dev/{name}.json", headers={"User-Agent": "Miffan-Model-Catalog/1.0"})
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def build_snapshot(models, providers):
    compact_models = {}
    for model_id, model in models.items():
        if not isinstance(model, dict):
            continue
        modalities = model.get("modalities")
        if (
            type(model.get("tool_call")) is not bool
            or type(model.get("reasoning")) is not bool
            or not isinstance(modalities, dict)
            or any(
                not isinstance(modalities.get(direction), list)
                or any(not isinstance(value, str) for value in modalities[direction])
                for direction in ("input", "output")
            )
        ):
            continue
        compact_models[model_id] = {
            "tool_call": model["tool_call"],
            "reasoning": model["reasoning"],
            "modalities": {key: modalities[key] for key in ("input", "output")},
        }

    canonical_keys = {norm(model_id) for model_id in models}
    alias_targets = {}
    for provider in providers.values():
        for model_id, model in provider.get("models", {}).items():
            canonical = model.get("canonical_model_id")
            alias = norm(model.get("id", model_id))
            if canonical in models and alias not in canonical_keys:
                alias_targets.setdefault(alias, set()).add(canonical)
    aliases = {
        alias: next(iter(targets))
        for alias, targets in alias_targets.items()
        if len(targets) == 1
    }
    return {
        "source": "models.dev",
        "fetchedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "models": dict(sorted(compact_models.items())),
        "aliases": dict(sorted(aliases.items())),
    }


def main():
    snapshot = build_snapshot(download("models"), download("api"))
    destination = Path(__file__).resolve().parents[1] / "app/src/main/assets/model-catalog.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(snapshot, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(snapshot['models'])} models and {len(snapshot['aliases'])} aliases to {destination}")


if __name__ == "__main__":
    main()
