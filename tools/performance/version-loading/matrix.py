"""Sequential full-generation matrix; never run benchmarks concurrently."""
from pathlib import Path
import argparse,json,subprocess,sys,time,os
here=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--cases')
p.add_argument('--jdk-root',type=Path,default=Path(os.environ.get('ProgramFiles','C:/Program Files'))/'Java')
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
cases=[('nf-121','neoforge','1.21.1',21,False),('fabric-121','fabric','1.21.1',21,False),
       ('fabric-120','fabric','1.20.1',21,False),('fabric-12111','fabric','1.21.11',21,False),
       ('fabric-261','fabric','26.1',25,False),('fabric-2611','fabric','26.1.1',25,False),
       ('fabric-2612','fabric','26.1.2',25,False),('fabric-262','fabric','26.2',25,False),
       ('fabric-263-cpu','fabric','26.3',25,False),('fabric-263-gpu','fabric','26.3',25,True),
       ('nf-121-tail','neoforge','1.21.1',21,False)]
rows=[]
for label,platform,target,jdk,gpu in cases:
    if a.cases and label not in a.cases.split(','):continue
    if (out/label).exists():raise RuntimeError('Use fresh case directory: '+label)
    print('START',label,flush=True);start=time.monotonic()
    cmd=[sys.executable,str(here/'run.py'),platform,target,str(out/label),'--jdk',str(a.jdk_root/f'jdk-{jdk}')]
    if gpu:cmd+=['--gpu']
    result=subprocess.run(cmd)
    rows.append({'label':label,'returncode':result.returncode,'runSeconds':time.monotonic()-start})
    status='matrix-status.json' if not a.cases else 'matrix-status-selected.json'
    (out/status).write_text(json.dumps(rows,indent=2))
    print('END',label,result.returncode,flush=True)
print('MATRIX_COMPLETE',flush=True)
