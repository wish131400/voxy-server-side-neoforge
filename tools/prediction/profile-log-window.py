import re,json,datetime,pathlib,sys
root=pathlib.Path(sys.argv[1]); rows=[]
for line in (root/'debug-snapshot.log').read_text(encoding='utf-8',errors='replace').splitlines():
    if 'VSS prediction render:' not in line:continue
    t=re.search(r' (\d\d:\d\d:\d\d\.\d+)\]',line)
    if not t:continue
    if not '00:21:35'<=t[1]<='00:22:35.999':continue
    row={'time':t[1]}
    for name in ('terrainFull','terrainPreview','meshUploads','gridCacheHits','gridSubmittedPoints','fullChunkLoads',
                 'nativeFeatures','compatibilityFeatures','chunks','surfaceDiskHits','surfaceMemoryHits','skippedFeatures',
                 'heapFreeMiB','accountedMiB','workerLimit','eligible','ready','uploadUs','coverageMs'):
        m=re.search(r'\b'+name+r'=([\d.]+)',line)
        if m:row[name]=float(m[1])
    m=re.search(r'stageTotalMs=\{([^}]+)',line)
    if m:row.update({k:float(v) for k,v in re.findall(r'(\w+)=(\d+)',m[1])})
    rows.append(row)
a,b=rows[0],rows[-1]
seconds=(datetime.datetime.fromisoformat('2026-09-10T'+b['time'])-datetime.datetime.fromisoformat('2026-09-10T'+a['time'])).total_seconds()
out={'start':a,'end':b,'seconds':seconds,'delta':{k:b[k]-a[k] for k in a if k!='time'},'rows':rows}
(root/'log-window.json').write_text(json.dumps(out,indent=2))
print(json.dumps({k:v for k,v in out.items() if k!='rows'},indent=2))
