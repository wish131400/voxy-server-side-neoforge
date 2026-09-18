import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

public class ScopeTerrainAudit {
    static Object call(Object o, String n, Object... a) throws Exception { return LiveRefinementSnapshot.call(o,n,a); }
    static Object field(Object o, String n) throws Exception { return LiveRefinementSnapshot.field(o,n); }
    public static void agentmain(String output, Instrumentation inst) throws Exception {
        Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
        Class<?> renderer=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
        Object ray=field(renderer,"viewRay"), origin=call(ray,"origin"), direction=call(ray,"direction");
        double ox=(double)field(origin,"x"),oy=(double)field(origin,"y"),oz=(double)field(origin,"z");
        double dx=(double)field(direction,"x"),dy=(double)field(direction,"y"),dz=(double)field(direction,"z");
        StringBuilder out=new StringBuilder("ray="+ray+"\nscoping="+field(renderer,"selectionScoping")+"\nfocus="+field(state,"lastFocus")+"\ndistance\tx\ty\tz\tsampledY\tresidentY\tspacing\ttile\n");
        for(Object m:((Map<?,?>)field(state,"MANAGERS")).values()) {
            if(!field(m,"dimension").toString().contains("overworld")) continue;
            Object snapshot=call(m,"renderSnapshot"), sampler=call(m,"sampler");
            for(int d=128;d<=6144;d+=128) {
                int x=(int)Math.floor(ox+dx*d),z=(int)Math.floor(oz+dz*d);
                Object t=call(snapshot,"coveringTileAtDetail",Math.floorDiv(x,16),Math.floorDiv(z,16),0);
                out.append(d).append('\t').append(x).append('\t').append(oy+dy*d).append('\t').append(z).append('\t')
                    .append(call(sampler,"surfaceY",x,z)).append('\t');
                if(t!=null) out.append(call(t,"heightAt",x-(int)call(t,"baseBlockX"),z-(int)call(t,"baseBlockZ"))).append('\t').append(call(t,"spacingBlocks")).append('\t').append(call(t,"key"));
                out.append('\n');
            }
        }
        Files.writeString(Path.of(output),out);
    }
}
