"""Add a bounded exact-answer cache to a frozen sampler, never production sources."""
from pathlib import Path
import hashlib
import json
import shutil


def instrument(output: Path):
    output = output.resolve()
    package = output / 'main-java/dev/xantha/vss/client/prediction'
    path = package / 'RustTerrainSampler.java'
    helper = Path(__file__).with_name('PrototypeBiomeAnswerCache.java')
    destination = package / helper.name
    text = path.read_text(encoding='utf-8')
    if destination.exists() or 'biomeAnswerCache' in text:
        raise ValueError('Snapshot already contains the biome-answer cache experiment')
    original_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    signature = ('    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> '
                 'noiseBiome(int x, int y, int z) {')
    wrapper = '''    private final PrototypeBiomeAnswerCache<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeAnswerCache = new PrototypeBiomeAnswerCache<>();
    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {
        handle(); // A cache hit still observes sampler close/cancellation/interruption.
        var cached = biomeAnswerCache.get(x, y, z);
        if (cached != null) return cached;
        var answer = noiseBiomeUncached(x, y, z);
        biomeAnswerCache.put(x, y, z, answer);
        return answer;
    }
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiomeUncached(int x, int y, int z) {'''
    diagnostic = '    String diagnostics() {return "backend="+ALGORITHM'
    diagnostic_patch = ('    String diagnostics() {return "biomeAnswerCache={"+biomeAnswerCache.diagnostics()+"},backend="+ALGORITHM')
    for old, new in [(signature, wrapper), (diagnostic, diagnostic_patch)]:
        if text.count(old) != 1:
            raise ValueError(f'Frozen sampler anchor mismatch: {old}')
        text = text.replace(old, new)
    # Only rename/wrap noiseBiome; its original body and the complete batch path remain intact.
    path.write_text(text, encoding='utf-8')
    shutil.copy2(helper, destination)
    evidence = {
        'scope': 'Frozen benchmark only; no production, native, or batch-query changes',
        'cache': 'Per sampler and per thread; 16384 direct-mapped slots; all three exact quart coordinates; final non-null Holder answers',
        'lifecycle': 'handle() before every lookup; misses execute the original method body; null answers are never cached',
        'counters': 'biomeAnswerCache in sampler diagnostics: hits, misses, replacements, nulls, tables, entries, backing-array byte upper bound',
        'originalSamplerSha256': original_sha,
        'sources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [path, destination]},
    }
    (output / 'biome-answer-cache-prototype.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('snapshot', type=Path)
    instrument(parser.parse_args().snapshot)
