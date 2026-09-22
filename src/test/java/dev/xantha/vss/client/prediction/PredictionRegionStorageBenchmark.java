package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

/** Opt-in read-only source benchmark. VSS_REGION_BENCH_SOURCE points at copied VPD fixtures. */
class PredictionRegionStorageBenchmark {
    record Sample(PredictionDiskCache.Key key, Path source) { }
    static volatile long sink;

    @Test void compareProductionRegionReaderWithLegacyFiles() throws Exception {
        String source=System.getenv("VSS_REGION_BENCH_SOURCE");
        String destination=System.getenv("VSS_REGION_BENCH_OUTPUT");
        assumeTrue(source!=null && destination!=null);
        Path input=Path.of(source).toAbsolutePath().normalize(), output=Path.of(destination).toAbsolutePath().normalize();
        assertFalse(output.startsWith(input)); assertFalse(Files.exists(output)); Files.createDirectories(output);
        List<Sample> samples=new ArrayList<>();
        try(var paths=Files.walk(input)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".vpd")).sorted().toList()) {
                Path relative=input.relativize(path);
                String[] type=relative.getName(0).toString().split("-");
                String[] coords=path.getFileName().toString().replace(".vpd","").split("_");
                samples.add(new Sample(new PredictionDiskCache.Key(Integer.parseInt(type[0]),Integer.parseInt(coords[0]),
                        Integer.parseInt(coords[1]),Integer.parseInt(type[1])),path));
            }
        }
        assertFalse(samples.isEmpty());
        long start=System.nanoTime();
        try(var storage=new PredictionRegionStorage(output)) {
            for(var sample:samples) {
                Path staged=Files.createTempFile(output,"bench-",".tmp");
                try { Files.copy(sample.source,staged,StandardCopyOption.REPLACE_EXISTING); storage.write(sample.key,staged); }
                finally { Files.deleteIfExists(staged); }
                assertArrayEquals(Files.readAllBytes(sample.source),storage.read(sample.key).bytes());
            }
        }
        double importMs=(System.nanoTime()-start)/1e6;
        Map<String,List<Double>> results=new TreeMap<>();
        long expected=-1;
        for(int round=0;round<9;round++) {
            Collections.shuffle(samples,new Random(32021+round));
            List<String> modes=new ArrayList<>(List.of("legacy-probe","region-probe","legacy-decode","region-decode"));
            Collections.shuffle(modes,new Random(round));
            for(String mode:modes) {
                start=System.nanoTime(); long total=0;
                try(var storage=new PredictionRegionStorage(output)) {
                    for(var sample:samples) {
                        if(mode.equals("region-probe")) { total+=storage.header(sample.key).length; continue; }
                        InputStream stream=mode.startsWith("legacy") ? new BufferedInputStream(Files.newInputStream(sample.source))
                                : new ByteArrayInputStream(storage.read(sample.key).bytes());
                        try(var in=new InflaterInputStream(stream)) {
                            if(mode.endsWith("probe")) total+=in.readNBytes(36).length;
                            else { byte[] buffer=new byte[32768]; for(int n;(n=in.read(buffer))!=-1;) total+=n; }
                        }
                    }
                }
                double ms=(System.nanoTime()-start)/1e6;
                if(mode.endsWith("decode")) { if(expected<0) expected=total; assertEquals(expected,total); }
                else assertEquals(samples.size()*36L,total);
                sink=total;
                if(round>=2) results.computeIfAbsent(mode,k->new ArrayList<>()).add(ms);
            }
        }
        long bytes;
        try(var files=Files.walk(output)) { bytes=files.filter(Files::isRegularFile).mapToLong(p->{
            try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}
        }).sum(); }
        StringBuilder report=new StringBuilder("records="+samples.size()+",regionBytes="+bytes+",rawBytes="+expected
                +",verifiedImportMs="+importMs+"\n");
        for(var entry:results.entrySet()) {
            var sorted=new ArrayList<>(entry.getValue()); Collections.sort(sorted);
            report.append(entry.getKey()).append(" medianMs=").append(sorted.get(3)).append(" rounds=").append(entry.getValue()).append('\n');
        }
        Files.writeString(output.resolve("benchmark.txt"),report);
        System.out.println(report);
    }
}
