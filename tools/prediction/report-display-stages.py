"""Produce the four-stage report from the shared JNI benchmark output."""
import json
from pathlib import Path
import sys

p=Path(sys.argv[1]).resolve()
summary=json.loads((p/'summary.json').read_text())
diffs=json.loads((p/'differences.json').read_text())
manifest=json.loads((p/'manifest.json').read_text())
lines=['# 展示采样四阶段实测','',f"每场景 {manifest['rounds']} 轮，独立 JVM/native world，交替顺序；表格为中位数，单位微秒/外部请求列。",'',
       '沿用 benchmark-meridian-current.py 的文档、种子、坐标和 8×8 批次。世界初始化、Java 输入准备、输出拷贝、TSV、植被、网格、磁盘和渲染不计入 JNI 时间。',
       '诊断库来自本次源码的独立副本，未打包进模组。完整诊断路径每场景的全部 40 字节记录 checksum 均与发布 DLL 一致。所有阶段各轮输出和计数稳定。', '',
       '阶段 1：仅密度边界，跳过生物群系准入、自适应插值和精确回退。',
       '阶段 2：增加生物群系、坡度和材质摘要；材质上下文使用廉价海平面候选，不进行液体校验，不输出有意义的水面结果。',
       '阶段 3：增加自定义含水层局部校验与水面生物群系检查；记录不通过的候选，但暂不执行精确回退。',
       '阶段 4：原生产完整展示路径，含颜色、自适应插值、缓存、准入和精确回退。',
       '前三阶段是消融实验，第三阶段发现错误也仍返回候选，不能作为可发布结果。由于完整路径会提前回退或减少采样点，不能简单相减将阶段差值视为独占成本。', '',
       '|输入|种子|场景|1 密度|2 +材质|3 +液体|4 完整诊断|完整发布包|精确入口|',
       '|---|---:|---|---:|---:|---:|---:|---:|---:|']
for e in summary:
    values='|'.join(f"{e['stages'][k]['median_us']:.3f}" for k in ['boundary','material','liquid','full','shipping','exact'])
    lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|{values}|")
lines+=['','## 完整路径归因','',
        '以下计时区间互不嵌套。精确回退包含其内部的密度、材质和含水层开销；密度列仅代表快速路径，不能把它视为全部密度计算。各区间独立取中位数，总和不保证严格等于总中位数。', '',
        '|输入|种子|场景|密度边界|材质+坡度|液体校验|精确回退|生物群系|工作区准备|颜色记录|回退列调用|密度列调用|自适应网格|',
        '|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for e in summary:
    s=e['stages']['full'];t=s['phase_us_per_request'];c=s['counts']
    fallback=sum(c.get(k,0) for k in ['fallback_hint_points','fallback_record_points','fallback_stateful_points'])
    values=[t.get('density_boundary',0),t.get('slope_materials',0),t.get('liquid_validation',0),t.get('exact_fallback',0),
            sum(t.get(k,0) for k in ['biome_hint','biome_floor','biome_water']),sum(t.get(k,0) for k in ['raw_setup','job_setup']),t.get('colors_record',0)]
    lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|"+'|'.join(f'{v:.3f}' for v in values)+f"|{fallback}|{c.get('density_columns',0)}|{c.get('adaptive_grids',0)}|")
lines+=['','回退列调用计数含内部自适应探测；不是唯一坐标数或一定与外部列数同分母。原始 counts 分别记录 hint、液体、水面/地面生物群系和材质拒绝。前三阶段拒绝项可重叠，不能相加解释为回退次数。预热和 warm 预填不计入这里的计数。', '']
if any('density_cell_proofs' in e['stages']['full']['counts'] for e in summary):
    lines += ['## 完整路径密度单元复用', '',
              '|输入|种子|场景|单元证明尝试|复用已有判断|跳过空单元|',
              '|---|---:|---|---:|---:|---:|']
    for e in summary:
        c=e['stages']['full']['counts']
        lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|"+'|'.join(str(c.get(k,0)) for k in ['density_cell_proofs','density_cell_reuses','density_cell_skips'])+'|')
    lines += ['', '复用包括已有的未知/非空判断；跳过数为逐列访问次数，同一单元可被多列跳过，不能视为不同单元的数量。', '']
if any('lattice_edges_loaded' in e['stages']['full']['counts'] for e in summary):
    lines += ['## 两角点区间计算', '',
              '|输入|种子|场景|两角点区间加载|复用区间端点|实际插值复用端点|',
              '|---|---:|---|---:|---:|---:|']
    for e in summary:
        c=e['stages']['full']['counts']
        lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|{c.get('lattice_edges_loaded',0)}|{c.get('lattice_edge_reuses',0)}|{c.get('interpolation_edge_reuses',0)}|")
    lines += ['', '操作数包含完整展示调用内部的精确回退；已存在的角点可继续从角点缓存读取，加载次数不等同于实际噪声求值次数。', '']
if any('initial_interval_searches' in e['stages']['full']['counts'] for e in summary):
    lines += ['## 初步地表高度搜索', '',
              '|输入|种子|场景|区间搜索|逐高度搜索|', '|---|---:|---|---:|---:|']
    for e in summary:
        c=e['stages']['full']['counts']
        lines.append(f"|{e['world']}|{e['seed']}|{e['scenario']}|{c.get('initial_interval_searches',0)}|{c.get('initial_linear_searches',0)}|")
    lines += ['', '只统计高度缓存未命中后的搜索；包含精确回退，区间搜索可使用编译高度计划或通用区间计算，不等同于外部请求列数。', '']
lines += [
        '## 高度与液体差异','',
        '以同输入同坐标的 VSS 精确入口为基准，不把它称为实际 Minecraft 存档真值。水面差异分开记录有无液体、液体种类，以及两边都有同种液体时的液面高度；避免把干地的 waterY=floor 误算成水面误差。', '',
        '|输入|种子|场景|阶段|高度变化/请求|高度 MAE|最大高度差|液体有无变化|液体种类变化|同种液体液面变化/有效列|液面最大差|',
        '|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|']
for d in diffs:
    w=d['water']
    tail='N/A|N/A|N/A|N/A' if w is None else f"{w['presence_changed']}|{w['kind_changed']}|{w['same_kind_level_changed']}/{w['same_kind_wet_columns']}|{w['same_kind_level_max']}"
    lines.append(f"|{d['world']}|{d['seed']}|{d['scenario']}|{d['stage']}|{d['height_changed']}/{d['count']}|{d['height_mae']:.3f}|{d['height_max']}|{tail}|")
lines+=['','## 复现与范围','',
        '```powershell',
        'python tools/prediction/benchmark-meridian-current.py --stage-breakdown --evidence-dir ../vss-performance-evidence/20260915 --output build/new-stage-run --extra-document build/perf-loading-replay/document.json',
        'python tools/prediction/report-display-stages.py build/new-stage-run','```','',
        'captured-derived 是原整合包生成配置去除 input_states 后的派生输入，测试 seed=0 和固定坐标，不是玩家当前世界实测。vanilla 使用 seed=0/917。这里只测单调用线程，不能推导整机 CPU、GPU、帧率或多线程锁竞争。',
        'tile66_warm 使用相同坐标预填；前三阶段同样具有点结果缓存，命中时不重新执行阶段工作。',
        '完整诊断与发布包计时可估计插桩干扰，但不同构建布局和微小耗时波动仍存在。源码替换脚本带匹配数量断言，源码变动时应检查而非静默套用。',
        '原始数据：manifest.json、runs.jsonl、summary.json、differences.json，runs/ 含每轮 TSV 和 stdout/stderr。',
        '诊断运行本身只复制并插桩源码，不修改生产采样实现、资源 DLL、游戏配置或 JAR；被测版本的实现变更见相应优化文档。']
(p/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('\n'.join(lines[:32]))
