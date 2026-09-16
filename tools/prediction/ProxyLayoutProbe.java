import dev.xantha.vss.client.prediction.RustWorldgenBackend;
import java.nio.*;
import java.nio.file.*;
public class ProxyLayoutProbe {
 public static void main(String[] args) throws Exception {
  System.load(Path.of(args[0]).toAbsolutePath().toString());
  long world=RustWorldgenBackend.create(0,0,Files.readString(Path.of(args[1])));
  int cx=3,cz=-13,minY=-64,height=384,ox=(cx-2)*16,oz=(cz-2)*16;
  int[][] columns=new int[6400][10];
  var buf=ByteBuffer.allocateDirect(256*40).order(ByteOrder.LITTLE_ENDIAN);
  for(int ax=cx-2;ax<=cx+2;ax++)for(int az=cz-2;az<=cz+2;az++) {
   if(RustWorldgenBackend.surfaceColumns(world,ax,az,buf)!=256)throw new AssertionError();
   for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int k=0;k<10;k++)
    columns[(ax*16+x-ox)*80+az*16+z-oz][k]=buf.getInt(((x*16+z)*10+k)*4);
  }
  long start=System.nanoTime();
  long proxy=RustWorldgenBackend.surfaceProxy(world,cx,cz);
  double createMs=(System.nanoTime()-start)/1e6;
  var blocks=ByteBuffer.allocateDirect(80*height*80*4).order(ByteOrder.LITTLE_ENDIAN);
  if(RustWorldgenBackend.readVolume(proxy,blocks)!=80*height*80)throw new AssertionError();
  int tested=0,mismatched=0;
  for(int x=0;x<80;x++)for(int z=0;z<80;z++) {
   var c=columns[x*80+z];int y=c[0]-1;
   if(y<minY||y>=minY+height||(c[3]&(1<<29))!=0)continue;
   int actual=blocks.getInt(((x*80+z)*height+y-minY)*4);
   tested++;
   if(actual!=c[4]) {
    if(mismatched<6)System.out.println("mismatch x="+(ox+x)+" z="+(oz+z)+" y="+y+" expectedState="+c[4]+" actualState="+actual);
    mismatched++;
   }
  }
  System.out.println("tested="+tested+" mismatched="+mismatched+" warmProxyCreateMs="+createMs);
  RustWorldgenBackend.close(proxy);RustWorldgenBackend.close(world);
 }
}
