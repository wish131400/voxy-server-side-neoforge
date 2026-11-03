"""Apply the follow-up scalar JNI experiment after the first matrix finishes."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args()
root=a.root.resolve();java=root/'baseline/main-java/dev/xantha/vss/client/prediction'
native=root/'native/src/jni_worldgen.rs'
with native.open('a',encoding='utf-8') as f:f.write('''
#[no_mangle]
pub extern "system" fn Java_dev_xantha_vss_client_prediction_RustWorldgenBackend_biomeIndex(
    mut e:JNIEnv,_c:JClass,id:jlong,x:jint,y:jint,z:jint)->jint {
    let result=guarded(|| {
        let _trace=crate::evaluation::timer(0);
        if x.abs_diff(0)>7_500_000 || z.abs_diff(0)>7_500_000 || y.abs_diff(0)>500_000 {
            return Err("biome quart coordinates".into());
        }
        world(id)?.noise_biome_index([x,y,z])
    });
    match result{Ok(index)=>index,Err(err)=>{fail(&mut e,err);-1}}
}
''')
p=java/'RustWorldgenBackend.java';s=p.read_text();s=s.replace('    public static native int abi();','    public static native int abi();\n    public static native int biomeIndex(long world,int x,int y,int z);');p.write_text(s)
p=java/'EvaluationJavaProbe.java';s=p.read_text().replace('static final boolean ENABLED=', 'static final boolean POINT=java.util.Arrays.asList(java.util.Objects.toString(System.getenv("VSS_EVAL_MODE"),"").split(",")).contains("point");static final boolean ENABLED=');p.write_text(s)
p=java/'RustTerrainSampler.java';s=p.read_text();old='''        int[] query = surfaceBiomePositions.get(), result = surfaceBiomes.get();
        query[0] = x; query[1] = y; query[2] = z;
        sampleBiomeIndices(query, 1, result);
        var biome = context.cacheBiomeHolder(result[0]);''';new='''        int index;
        if(EvaluationJavaProbe.POINT) {
            int nativeIndex=RustWorldgenBackend.biomeIndex(handle(),x,y,z);
            if(nativeIndex<0 || nativeIndex>=nativeBiomeMapping.length)throw new IllegalStateException("Invalid native biome index");
            biomeNativeBatches.increment();biomeNativePoints.increment();
            index=nativeBiomeMapping[nativeIndex];
            if(index<0) {biomeJavaFallbacks.increment();return context.noiseBiome(x,y,z);}
        } else {
            int[] query = surfaceBiomePositions.get(), result = surfaceBiomes.get();
            query[0] = x; query[1] = y; query[2] = z;
            sampleBiomeIndices(query, 1, result);index=result[0];
        }
        var biome = context.cacheBiomeHolder(index);'''
assert s.count(old)==1;s=s.replace(old,new);p.write_text(s)
print('Prepared scalar JNI experiment',root)
