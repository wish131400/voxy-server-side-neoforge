"""Inspect captured worldgen plans and JNI counters without running Minecraft.

This reports structural counts, not execution frequencies or performance gains.
Named material rules are expanded and only immutable biome tests are folded.
No noise, random, density function, or mod callback is evaluated.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


def identifier(name: str) -> str:
    return name if ":" in name else "minecraft:" + name


def resolve(value, registry: str, document: dict, stack=()):
    if not isinstance(value, str):
        return value
    name = identifier(value)
    key = (registry, name)
    if key in stack or len(stack) >= 128:
        raise ValueError(f"cyclic/excessive {registry} reference: {name}")
    return resolve(document[registry][name], registry, document, (*stack, key))


def condition(value, document):
    value = resolve(value, "material_conditions", document)
    if value["type"] == "minecraft:not":
        return {**value, "invert": condition(value["invert"], document)}
    return value


def rule(value, document, depth=0):
    if depth > 128:
        raise ValueError("excessive expanded material tree depth")
    value = resolve(value, "material_rules", document)
    kind = value["type"]
    if kind == "minecraft:sequence":
        return {**value, "sequence": [rule(v, document, depth + 1) for v in value["sequence"]]}
    if kind == "minecraft:condition":
        return {**value, "if_true": condition(value["if_true"], document),
                "then_run": rule(value["then_run"], document, depth + 1)}
    if kind == "terrablender:merged":
        return {**value, "base": rule(value["base"], document, depth + 1),
                "sources": {k: rule(v, document, depth + 1) for k, v in value["sources"].items()}}
    return value


def biome_test(value, biome, document):
    kind = value["type"]
    if kind == "minecraft:biome":
        members = value["biome_is"]
        if isinstance(members, str) and members.startswith("#"):
            members = document["biome_tags"][identifier(members[1:])]
        elif isinstance(members, str):
            members = [members]
        return biome in [identifier(name) for name in members]
    if kind == "minecraft:not":
        known = biome_test(value["invert"], biome, document)
        return None if known is None else not known
    return None


def fold(value, biome, document):
    kind = value["type"]
    if kind == "minecraft:condition":
        known = biome_test(value["if_true"], biome, document)
        if known is False:
            return None
        child = fold(value["then_run"], biome, document)
        if known is True:
            return child
        return {**value, "then_run": child or {"type": "minecraft:sequence", "sequence": []}}
    if kind == "minecraft:sequence":
        # Keep order and empty sequences, as Rule::for_biome does.
        return {**value, "sequence": [v for x in value["sequence"]
                                    if (v := fold(x, biome, document)) is not None]}
    if kind == "terrablender:merged":
        selected = value["sources"].get(biome.split(":", 1)[0])
        candidates = ([selected] if selected is not None else []) + [value["base"]]
        return {"type": "minecraft:sequence", "sequence": [v for x in candidates
                if (v := fold(x, biome, document)) is not None]}
    return value


def describe(root):
    counts = Counter()
    noises_2d = Counter()
    noises_3d = Counter()

    def visit(value):
        if isinstance(value, dict):
            kind = value.get("type")
            if kind:
                counts[kind] += 1
            if kind == "minecraft:noise_threshold":
                target = noises_3d if value.get("is_3d", False) else noises_2d
                target[identifier(value["noise"])] += 1
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(root)
    return {"typed_nodes": sum(counts.values()), "node_types": dict(sorted(counts.items())),
            "noise_2d_references": dict(sorted(noises_2d.items())),
            "noise_3d_references": dict(sorted(noises_3d.items())),
            "duplicate_2d_noise_references": sum(n - 1 for n in noises_2d.values()),
            "duplicate_3d_noise_references": sum(n - 1 for n in noises_3d.values())}


def inspect_document(path):
    raw = path.read_bytes()
    document = json.loads(raw)
    settings = document["settings"]
    source = settings.get("material_rule", settings.get("surface_rule"))
    if source is None:
        raise ValueError("missing surface/material rule")
    expanded = rule(source, document)
    per_biome = {name: describe(fold(expanded, name, document))
                 for name in sorted(document["biomes"])}
    return {"sha256": hashlib.sha256(raw).hexdigest(), "expanded": describe(expanded),
            "per_biome": per_biome,
            "note": "Static tree references; conditions may short-circuit. Not measured calls or speedup."}


def inspect_diagnostics(path):
    raw = path.read_bytes()
    match = re.search(r"biomeIdentity=\{nativeBatches=(\d+),nativePoints=(\d+),javaFallbacks=(\d+)\}",
                      raw.decode("utf-8-sig"))
    if not match:
        raise ValueError("missing native biome counters")
    batches, points, fallback = map(int, match.groups())
    if points < batches:
        raise ValueError("counters violate positive-size Java batch invariant")
    lower = max(0, 2 * batches - points)
    return {"sha256": hashlib.sha256(raw).hexdigest(), "native_batches": batches,
            "native_points": points, "java_fallbacks": fallback,
            "points_per_batch": points / batches if batches else None,
            "singleton_batch_lower_bound": lower,
            "singleton_fraction_lower_bound": lower / batches if batches else None,
            "note": "Cumulative counters, including warmup. Lower bound, not a recorded histogram."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document", nargs=2, action="append", default=[], metavar=("LABEL", "PATH"))
    parser.add_argument("--diagnostics", nargs=2, action="append", default=[], metavar=("LABEL", "PATH"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = {"schema": 1, "kind": "static-inspection-no-timing", "documents": {}, "diagnostics": {}}
    for label, path in args.document:
        result["documents"][label] = inspect_document(Path(path))
    for label, path in args.diagnostics:
        result["diagnostics"][label] = inspect_diagnostics(Path(path))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for label, row in result["documents"].items():
        ranked = sorted(row["per_biome"].items(), key=lambda p: p[1]["duplicate_2d_noise_references"], reverse=True)
        print(json.dumps({"label": label, "expanded_nodes": row["expanded"]["typed_nodes"],
                         "example_biomes": {k: v for k, v in ranked[:3]}}, ensure_ascii=False))
    for label, row in result["diagnostics"].items():
        print(json.dumps({"label": label, **row}, ensure_ascii=False))


if __name__ == "__main__":
    main()
