"""Install the exact scalar JNI path after full-path candidate acceptance."""
from pathlib import Path
import argparse,shutil
p=argparse.ArgumentParser();p.add_argument('project',type=Path);a=p.parse_args();project=a.project.resolve()
java=project/'src/main/java/dev/xantha/vss/client/prediction'
samplers=[java/'RustTerrainSampler.java',*(project/'src/versions').glob('*/java/dev/xantha/vss/client/prediction/RustTerrainSampler.java')]
for source in samplers:
 assert source.read_text().count('    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {')==1,('This patch targets existing Fabric scalar wrappers; adapt other loaders explicitly',source)
p=java/'RustWorldgenBackend.java';s=p.read_text();needle='    public static native int biomeIndices(long world, ByteBuffer quartXyz, ByteBuffer output, int count);'
assert s.count(needle)==1;s=s.replace(needle,needle+'''
    /** Exact single noise-quart query, same index table and validation as biomeIndices. */
    public static native int biomeIndex(long world, int x, int y, int z);
''');p.write_text(s,encoding='utf-8')
p=project/'tools/rust/vss-native-core/src/jni_worldgen.rs'
with p.open('a',encoding='utf-8') as f:f.write('''
/// Scalar counterpart to biomeIndices: identical Raw sampling without direct-buffer setup.
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_biomeIndex(
    mut e: JNIEnv, _c: JClass, id: jlong, x: jint, y: jint, z: jint,
) -> jint {
    let result = guarded(|| {
        if x.abs_diff(0) > 7_500_000 || z.abs_diff(0) > 7_500_000 || y.abs_diff(0) > 500_000 {
            return Err("biome quart coordinates".into());
        }
        world(id)?.noise_biome_index([x, y, z])
    });
    match result { Ok(index) => index, Err(err) => { fail(&mut e, err); -1 } }
}
''')
for source in samplers:
 s=source.read_text();needle='    @Override net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {'
 assert s.count(needle)==1,source
 s=s.replace(needle,'    private volatile boolean legacyScalarBiome;\n'+needle)
 needle='''        int[] query = surfaceBiomePositions.get(), result = surfaceBiomes.get();
        query[0] = x; query[1] = y; query[2] = z;
        sampleBiomeIndices(query, 1, result);
        var biome = context.cacheBiomeHolder(result[0]);'''
 assert s.count(needle)==1,source
 s=s.replace(needle,'''        if (!legacyScalarBiome) {
            try {
                int nativeIndex = RustWorldgenBackend.biomeIndex(handle(), x, y, z);
                if (nativeIndex < 0 || nativeIndex >= nativeBiomeMapping.length)
                    throw new IllegalStateException("Invalid native biome index");
                biomeNativeBatches.increment(); biomeNativePoints.increment();
                int index = nativeBiomeMapping[nativeIndex];
                var biome = index < 0 ? null : context.cacheBiomeHolder(index);
                if (biome != null) return biome;
                biomeJavaFallbacks.increment();
                return context.noiseBiome(x, y, z);
            } catch (UnsatisfiedLinkError olderLibrary) {
                legacyScalarBiome = true;
            }
        }
        int[] query = surfaceBiomePositions.get(), result = surfaceBiomes.get();
        query[0] = x; query[1] = y; query[2] = z;
        sampleBiomeIndices(query, 1, result);
        var biome = context.cacheBiomeHolder(result[0]);''')
 source.write_text(s,encoding='utf-8')
shutil.copy2(Path(__file__).with_name('RustScalarBiomeTest.java'),project/'src/test/java/dev/xantha/vss/client/prediction/RustScalarBiomeTest.java')
print('Installed exact scalar entry:',project)
