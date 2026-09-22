"""Read-only export of canonical seam summaries from validated finished-mesh region records."""
from pathlib import Path
import struct,zlib,csv,sys,collections
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
groups=collections.defaultdict(list)
for f in sorted(root.rglob('*.vpr')):
 if not f.parent.name.startswith('2-'):continue
 with f.open('rb') as stream:
  header=stream.read(16)
  if header!=struct.pack('>4i',0x56535052,1,1024,64):continue
  for slot in range(1024):
   entry=stream.read(64)
   if len(entry)!=64:break
   sid,state,offset,size=struct.unpack_from('>iiqi',entry)
   crc=struct.unpack_from('>I',entry,60)[0]
   if sid==slot+1 and state==1 and zlib.crc32(entry[:60])==crc and 0<size<=32*1024**2 and offset>=65552 and offset+size<=f.stat().st_size:
    groups[int(f.parent.name[2:])].append((f,slot,offset,size))
rows=[];total=0;errors=[]
for lod,entries in sorted(groups.items()):
 # deterministic spread across each detail group, max 16 exported per group
 chosen=[entries[i*len(entries)//min(16,len(entries))] for i in range(min(16,len(entries)))]
 for f,slot,offset,size in chosen:
  try:
   with f.open('rb') as stream:stream.seek(offset);compressed=stream.read(size)
   decoder=zlib.decompressobj();record=decoder.decompress(compressed,17*1024**2)
   assert decoder.eof and not decoder.unused_data and len(record)<=17*1024**2
   assert struct.unpack_from('>i',record)[0]==0x56535044 and struct.unpack_from('>i',record,16)[0]==2
   length=struct.unpack_from('>i',record,32)[0];mesh=record[36:];assert length==len(mesh)
   assert struct.unpack_from('>i',mesh)[0]==3, 'finished mesh version is not 3'
   axis=struct.unpack_from('>i',mesh,36)[0];n=struct.unpack_from('>i',mesh,65)[0];at=69
   for _ in range(n):
    at+=4;sizeUtf=struct.unpack_from('>H',mesh,at)[0];at+=2+sizeUtf
    model=mesh[at];at+=1
    if model:at+=37
    at+=8
   count=struct.unpack_from('>i',mesh,at)[0];at+=4+4*count+80
   seam=mesh[at:];assert len(seam)>0
   cursor=0
   for stride in [4,4,1,8,4,4]:
    count=struct.unpack_from('>i',seam,cursor)[0];assert count>=0;cursor+=4+stride*count
   assert cursor==len(seam)
   if total+len(seam)>128*1024**2:continue
   name=f'seam-{len(rows):03d}.bin';(out/name).write_bytes(seam);total+=len(seam)
   rows.append((name,lod,axis,len(seam),count*4,str(f),slot))
  except Exception as e:errors.append((str(f),slot,str(e)))
with (out/'manifest.csv').open('w',newline='',encoding='utf8') as stream:
 w=csv.writer(stream);w.writerow(['file','lod','axis','bytes','wallBytes','source','slot']);w.writerows(rows)
(out/'source.txt').write_text(f'root={root}\nrecords={sum(map(len,groups.values()))}\ntiles={len(rows)}\nbytes={total}\nerrors={errors}',encoding='utf8')
print('exported',len(rows),'bytes',total,'wallBytes',sum(r[4] for r in rows),'errors',errors[:3])
