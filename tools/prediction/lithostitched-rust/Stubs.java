package dev.xantha.vss.client.prediction;
class PredictionVoxySource {record Detail(int x,int z,int lod){}}
class PredictionWorkView {static final int VISIBLE=0,BUFFER=1,BACKGROUND=3;}
class PredictionCpuCachePolicy {static final long COLD_AGE_NANOS=20000000000L;}
class PredictionClientPreparation<I,O> {
 static final java.util.concurrent.Executor WORKER=Runnable::run;
 PredictionClientPreparation(java.util.concurrent.Executor e,java.util.function.Function<I,O> f){}
 boolean pending(){return false;} O poll(){return null;} void request(I i){} void clear(){}
}
