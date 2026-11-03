from pathlib import Path
import argparse,json,collections,statistics
parser=argparse.ArgumentParser()
parser.add_argument('directory',type=Path)
parser.add_argument('--mode', default='baseline')
args=parser.parse_args()
root=args.directory
rows=json.loads((root/'results.json').read_text())
formal=[r for r in rows if not r['warmup'] and r['mode']==args.mode]
assert formal, f'No formal rounds for mode {args.mode}'
costs=collections.defaultdict(lambda:collections.defaultdict(int))
for r in formal:
    d=json.loads((root/f"feature-costs-{r['mode']}-{r['round']}.json").read_text())
    for c in d['features']:
        total=costs[c['id']]
        for k,v in c.items():
            if isinstance(v,int):total[k]+=v
            else:total[k]=v
n=len(formal)
ordered=sorted(costs.values(),key=lambda c:c['nanos'],reverse=True)
table=[]
for c in ordered:
    table.append(dict(id=c['id'],type=c['type'],modifiers=c['modifiers'],
        meanMs=c['nanos']/1e6/n,exactMs=c['exactNanos']/1e6/n,displayMs=c['displayNanos']/1e6/n,
        biomeMs=c['biomeNanos']/1e6/n,actualBiomeMs=c['biomeLoadNanos']/1e6/n,
        exactPoints=c['exactPoints']/n,noWriteCalls=c['noWriteCalls']/n,calls=c['calls']/n,
        noWriteMs=c['noWriteNanos']/1e6/n,noWriteExactMs=c['noWriteExactNanos']/1e6/n,
        nativeCalls=c['nativeCalls']/n,javaCalls=c['javaCalls']/n,treeCalls=c['treeCalls']/n))
summary={'mode':args.mode,'rounds':n,'wallMs':statistics.mean(r['wallMs'] for r in formal),
         'decorationMs':statistics.mean(r['decorationMs'] for r in formal),
         'features':table,'exactMs':sum(c['exactMs'] for c in table),
         'biomeMs':sum(c['biomeMs'] for c in table),'actualBiomeMs':sum(c['actualBiomeMs'] for c in table),
         'noWriteMs':sum(c['noWriteMs'] for c in table if not c['id'].startswith('@')),
         'noWriteExactMs':sum(c['noWriteExactMs'] for c in table),
         'exactPoints':sum(c['exactPoints'] for c in table)}
(root/f'hotspot-summary-{args.mode}.json').write_text(json.dumps(summary,indent=2))
print({k:v for k,v in summary.items() if k!='features'})
for c in table[:22]:
    print(c['id'],round(c['meanMs'],1),'exact',round(c['exactMs'],1),'biome',round(c['biomeMs'],1),
          'noWrite',round(c['noWriteMs'],1),int(c['noWriteCalls']),'/',int(c['calls']),c['modifiers'])
