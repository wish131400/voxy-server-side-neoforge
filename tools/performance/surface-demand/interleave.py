"""Interleave regions across retained, independent contexts to limit timing drift."""
from pathlib import Path
import argparse

p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
f=a.output/'harness-java/dev/xantha/vss/client/prediction/PredictionGenerationComparison.java'
t=f.read_text(encoding='utf-8')
def replace(old,new):
    global t
    assert t.count(old)==1,(old,t.count(old))
    t=t.replace(old,new)
replace('        for(String mode:modes) {','''        record Run(String mode,long handle,RustTerrainSampler sampler,PredictionVegetation vegetation,HorizontalSharing.Store store) {}
        var contexts=new ArrayList<Run>();
        try {
        for(String mode:modes) {''')
start=t.index('            try(var sampler=new RustTerrainSampler(handle,profile,context)) {')
end=t.index('                    int cx=8192+round*512',start)
t=t[:start]+'''            var sampler=new RustTerrainSampler(handle,profile,context);
            sampler.experimentPrepare();
            var vegetation=new PredictionVegetation(sampler,null,true);
            if(!vegetation.available())throw new AssertionError("Vegetation unavailable");
            contexts.add(new Run(mode,handle,sampler,vegetation,HorizontalSharing.current));
        }
        for(int round=-warmups;round<rounds;round++) {
            var order=new ArrayList<>(contexts);
            // Keep oracle first. Reverse the candidate order on every other region.
            if((round&1)!=0)Collections.reverse(order.subList(1,order.size()));
            for(var run:order) {
                    var mode=run.mode();long handle=run.handle();
                    var sampler=run.sampler();var vegetation=run.vegetation();
                    DecorationExperimentSettings.select(mode);
                    HorizontalSharing.mode=mode;HorizontalSharing.current=run.store();
''' +t[end:]
replace('''            }
            // Release discarded contexts between variants. Outside all measurement windows.
            System.gc();
        }
        Files.writeString(output.resolve("prediction-complete.txt"),"success\\n");''','''
            System.gc(); // Outside measurement windows; all mode contexts remain independent.
        }
        } finally {for(var run:contexts)run.sampler().close();}
        Files.writeString(output.resolve("prediction-complete.txt"),"success\\n");''')
# The original try/context closing brace is now the outer round closing brace.
t=t.replace('metadata.put("scope",','metadata.put("order","region-interleaved, candidates reverse on odd regions, retained contexts");\n        metadata.put("scope",',1)
f.write_text(t,encoding='utf-8')
print(f)
