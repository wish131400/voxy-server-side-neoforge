"""Keep the original biome-query order: its last-leaf ties are observable."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
f=a.output/'native/src/backend/exterior.rs';t=f.read_text()
start=t.index('        let rules_scope = crate::prof::Scope::new(&crate::prof::SURFACE.apply_rules);')
callback=t.index('        if self.surface.demand_enabled() {',start)
loop=t.index('        let positions = [',callback)
end=t.index('                    Ok(Some(heights))',loop)
original=t[loop:end]
setup=original.replace('        let mut heights = [top; 4];','        let mut saved_heights = [top; 4];')
setup=setup.replace('            if (xx, zz) == (x, z) {','            if (xx, zz) >= (x, z) {')
setup=setup.replace('            heights[i] = h;','            saved_heights[i] = h;')
replacement='''        let mut heights = saved_heights;
        for (i, &(xx,zz)) in positions.iter().enumerate() {
            if (xx,zz)>(x,z) {heights[i]=job.surface_top(xx,zz).map_or(t.min_y,|(y,_)|y+1);}
        }
'''
t=t[:loop]+replacement+t[end:]
marker='''        if self.surface.demand_enabled() {
            let success='''
assert t.count(marker)==1
t=t.replace(marker,'''        if self.surface.demand_enabled() {
'''+setup+'''            let success=''',1)
f.write_text(t,encoding='utf-8')
print(f)
