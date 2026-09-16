"""Summarize benchmark-meridian-current.py output, including sampled errors."""
import csv
import json
from pathlib import Path
import statistics
import sys

p = Path(sys.argv[1]).resolve()
summary = json.loads((p / 'summary.json').read_text())
manifest = json.loads((p / 'manifest.json').read_text())
diffs = []
for entry in summary:
    dest = p / 'runs/0' / entry['world'] / str(entry['seed']) / entry['scenario']
    def records(version):
        return list(csv.DictReader(next((dest / version).glob('*.tsv')).open(), delimiter='\t'))
    exact = records('exact')
    for version in ['display', 'preview', 'reference']:
        if version not in entry['versions']:
            continue
        other = records(version)
        assert len(exact) == len(other)
        assert all((a['x'], a['z']) == (b['x'], b['z']) for a, b in zip(exact, other))
        delta = [abs(int(a['height']) - int(b['height'])) for a, b in zip(exact, other)]
        diffs.append(dict(world=entry['world'], seed=entry['seed'], scenario=entry['scenario'], version=version,
                          count=len(delta), height_changed=sum(d != 0 for d in delta), height_mae=statistics.mean(delta),
                          height_max=max(delta), water_changed=sum(a['waterY'] != b['waterY'] or a['fluid'] != b['fluid'] for a, b in zip(exact, other)),
                          materials=None if version == 'reference' else {k: sum(a[k] != b[k] for a, b in zip(exact, other)) for k in ['top', 'under', 'deep']}))
(p / 'differences.json').write_text(json.dumps(diffs, indent=2))
lines = ['# 当前 VSS 与 Meridian 0.1.4 原生采样复测', '',
         f"测试时间：{manifest['time']}。每项 {manifest['rounds']} 轮，独立 JVM/native world，交替运行。下表为中位数，单位 µs/请求列。", '',
         ('测量候选 DLL（native_override，尚非打包结果）' if manifest.get('native_override') else '测量当前打包 DLL') + ' 的 JNI 调用耗时。VSS 使用 8×8 小批，Meridian 使用 sampleGrid；66×66 共 4356 列。',
         '32 个远处点预热后测未访问区域；warm 项明确先查询相同坐标。初始化、Java 整理/拷贝、植被放置、网格、磁盘、GPU 不计入。',
         '双方返回语义不同，数值是各自 API 的成本对照，不是同等输出算法加速比；不代表整个游戏加载倍率。', '',
         '|输入|种子|场景|VSS 精确|旧展示|新展示|VSS 预览|Meridian|新展示/参考耗时|旧展示/新展示耗时|',
         '|---|---:|---|---:|---:|---:|---:|---:|---:|---:|']
for e in summary:
    v = e['versions']
    values = [f"{v[k]['median_us']:.3f}" if k in v else '—' for k in ['exact', 'baseline', 'display', 'preview', 'reference']]
    ratio = f"{v['display']['median_us'] / v['reference']['median_us']:.2f}×" if 'reference' in v else '—'
    speedup = f"{v['baseline']['median_us'] / v['display']['median_us']:.2f}×" if 'baseline' in v else '—'
    lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|{'|'.join(values)}|{ratio}|{speedup}|")
lines += ['', '## 数值差异与限制', '',
          f"各后端每场景 {manifest['rounds']} 轮完整输出 checksum 稳定。逐点对照以 VSS 精确入口为基准；Meridian 固液边界语义可能不同，差异不能直接判为错误。跨库材质 ID 不可直接比较。", '',
          '|输入|种子|场景|对照入口|高度变化列/总列|高度平均绝对差|高度最大差|液面/液体变化列|',
          '|---|---:|---|---|---:|---:|---:|---:|']
for d in diffs:
    lines.append(f"|{d['world']}|{d['seed']}|{d['scenario']}|{d['version']}|{d['height_changed']}/{d['count']}|{d['height_mae']:.3f}|{d['height_max']}|{d['water_changed']}|")
lines += ['', 'captured-derived 是之前整合包生成文档去除 input_states 后的派生输入，保留生成配置。本次固定 seed=0 和基准坐标，只测 VSS；并非玩家当前位置/原种子的现场复现，也没有在该输入上测 Meridian。',
          'warm 仅对比直接原生 API，未经过 Meridian Java SampleStore，不能推导其游戏热加载更慢。',
          'min/max、初始化耗时、逐轮记录见 summary.json 和 timings.csv；逐点 TSV 位于 runs。', '',
          '## 冻结产物', '', f"JAR SHA-256：`{manifest['jar_sha256']}`", '',
          f"当前 DLL：`{manifest['libraries']['current']}`", '',
          f"Meridian 0.1.4 DLL：`{manifest['libraries']['reference']}`", '',
          '库和输入身份以 manifest.json 为准；native_override 非空时，JAR 哈希仅记录原包来源，不能代表候选 DLL。', '',
          '工具与冻结输入用于独立进程测试，不修改游戏配置或安装包。']
(p / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('\n'.join(lines[:28]))
