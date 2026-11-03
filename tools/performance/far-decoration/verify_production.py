"""Ensure the experiment really used the current production tree, with only declared test hooks."""
from pathlib import Path
import argparse, ast, hashlib, json
p=argparse.ArgumentParser();p.add_argument('snapshot',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[3]
tree=ast.parse((Path(__file__).parent/'prepare.py').read_text(encoding='utf-8'))
changes=next(ast.literal_eval(n.value) for n in ast.walk(tree) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='changes' for t in n.targets))
files={}
for folder,target in [('src/main/java','main-java'),('src/main/resources','main-resources')]:
    for f in (root/folder).rglob('*'):
        if not f.is_file():continue
        copied=a.snapshot/target/f.relative_to(root/folder)
        if folder.endswith('java'):
            text=copied.read_text(encoding='utf-8')
            for name,old,new in reversed(changes):
                if f.name==name:
                    assert text.count(new)==1,(f,new[:80],text.count(new))
                    text=text.replace(new,old)
            assert text==f.read_text(encoding='utf-8'),f
        else:assert copied.read_bytes()==f.read_bytes(),f
        files[str(f.relative_to(root))]=hashlib.sha256(f.read_bytes()).hexdigest()
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps({'productionMatchesSnapshotMinusDeclaredHooks':True,'files':files},indent=2),encoding='utf-8')
print('Production verified:',len(files),'files')
