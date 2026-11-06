package dev.xantha.vss.client.prediction;
public class JitterReplay {
 public static void main(String[] args) {
  var old=new LegacyPredictionVoxyDetailQueue(4096);var now=new PredictionVoxyDetailQueue(4096);
  for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++) {
   var d=new PredictionVoxySource.Detail(x,z,0);old.request(d,false);now.request(d,false);
  }
  long before=old.evicted(),after=now.evicted();
  for(int i=0;i<240;i++) {
   double x=8*Math.sin(i*.11),z=8*Math.cos(i*.11);
   old.priority(x,z,d->0);now.priority(x,z,d->0);
  }
  System.out.println("{\"capacity\":4096,\"demand\":6561,\"viewUpdates\":240,\"oldReorders\":"+(old.evicted()-before)+",\"newReorders\":"+(now.evicted()-after)+",\"pending\":"+now.pending()+",\"waiting\":"+now.waiting()+"}");
 }
}
