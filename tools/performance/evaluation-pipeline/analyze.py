"""Summarise complete-path timings and nested attribution without adding them."""
from pathlib import Path
import argparse,json,statistics
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args()
summaries={};oracle=None
for run in sorted((a.root/'runs').iterdir()):
 if not (run/'complete.txt').exists():continue
 rows=json.loads((run/'results.json').read_text());formal=[r for r in rows if not r['warmup']]
 manifest=json.loads((run/'manifest.json').read_text())
 hashes={r['round']:r['hash'] for r in rows}
 if run.name=='original':oracle=hashes
 summary={'mode':manifest['mode'],'trace':manifest['trace'],'rounds':len(formal),
          **{key:statistics.mean(r[key] for r in formal) for key in ['wallMs','decorationMs','terrainMs','encodingMs','allocatedMiB']},'hashes':hashes}
 if manifest['trace']:
  last=rows[-1];before=next((r for r in reversed(rows) if r['warmup']),None)
  summary['javaBiome']={key:(last['biomeJavaProbe'][key]-(before['biomeJavaProbe'][key] if before else 0))/len(formal) for key in ['calls','nanos']}
  def probe(r):
   text=(run/f'diagnostics-{r["round"]}.txt').read_text().splitlines()[0]
   return json.loads(text.split('groundQueries=',1)[1])['evaluationProbe']
  end=probe(last);begin=probe(before) if before else {}
  summary['nativeProbe']={name:{key:(entry[key]-begin.get(name,{}).get(key,0))/len(formal) for key in ['calls','nanos']} for name,entry in end.items()}
 summaries[run.name]=summary
for name,s in summaries.items():
 if oracle:s['hashesMatchOriginal']=all(oracle.get(int(r))==h for r,h in s['hashes'].items())
 print(name,json.dumps({k:v for k,v in s.items() if k!='hashes'},ensure_ascii=False))
(a.root/'summary.json').write_text(json.dumps(summaries,indent=2),encoding='utf-8')
