"""Apply only the additive light feature entry to an explicitly named project."""
from pathlib import Path
import argparse,shutil
p=argparse.ArgumentParser();p.add_argument('project',type=Path);a=p.parse_args();project=a.project.resolve()
java=project/'src/main/java/dev/xantha/vss/client/prediction'
p=java/'RustWorldgenBackend.java';s=p.read_text();needle='    public static native String placedFeature(long result, String name, int chunkX, int chunkZ, int globalIndex, int step);'
assert s.count(needle)==1
s=s.replace(needle,needle+'''
    /** Same transaction without a diagnostic JSON result: zero/one is the original placement boolean. */
    public static native int placedFeatureStatus(long result, String name, int chunkX, int chunkZ, int globalIndex, int step);
    private static volatile boolean legacyFeatureStatus;
    static void applyPlacedFeature(long result, String name, int chunkX, int chunkZ, int globalIndex, int step) {
        if (!legacyFeatureStatus) {
            try {
                placedFeatureStatus(result, name, chunkX, chunkZ, globalIndex, step);
                return;
            } catch (UnsatisfiedLinkError olderLibrary) {
                legacyFeatureStatus = true;
            }
        }
        placedFeature(result, name, chunkX, chunkZ, globalIndex, step);
    }
''');p.write_text(s,encoding='utf-8')
p=java/'RustVegetationStage.java';s=p.read_text();old='RustWorldgenBackend.placedFeature(volume, features.name(index), x, z, index, step);'
assert s.count(old)==1;p.write_text(s.replace(old,old.replace('.placedFeature(','.applyPlacedFeature(')),encoding='utf-8')
p=project/'tools/rust/vss-native-core/src/jni_worldgen.rs'
with p.open('a',encoding='utf-8') as f:f.write('''
/// Production placement keeps the same transaction/random stream and skips JSON serialization.
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_placedFeatureStatus(
    mut e: JNIEnv, _c: JClass, id: jlong, name: JString,
    chunk_x: jint, chunk_z: jint, index: jint, step: jint,
) -> jint {
    let result = guarded(|| {
        let name = text(&mut e, name, 32768)?;
        let v = volume(id)?;
        let mut v = v.lock().map_err(|_| "volume lock")?;
        let owner = v.owner.clone();
        let (placed, _) = owner.placed(&mut v.volume, &name, chunk_x, chunk_z, index, step)?;
        Ok(i32::from(placed))
    });
    match result { Ok(status) => status, Err(err) => { fail(&mut e, err); -1 } }
}
''')
# This fixture uses JNI/JSON only and is independent of game-version naming.
test=project/'src/test/java/dev/xantha/vss/client/prediction/RustFeatureStatusTest.java'
shutil.copy2(Path(__file__).with_name('RustFeatureStatusTest.java'),test)
print('Installed additive feature status entry:',project)
