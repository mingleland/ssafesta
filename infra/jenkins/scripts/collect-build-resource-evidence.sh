#!/usr/bin/env bash
# Unity 빌드 전후 호스트 자원 스냅샷을 남긴다 (Batch 2) — Runner/host 용량 결정은 이 파일의 누적치로만 한다.
#   collect-build-resource-evidence.sh start OUT.json      스냅샷 시작 (기존 파일 덮어씀)
#   collect-build-resource-evidence.sh finish OUT.json [artifact...]   종료 스냅샷 + duration + PSI/IO 증분 + artifact 크기
set -euo pipefail
[[ $# -ge 2 && ( "$1" == start || "$1" == finish ) ]] || { echo 'Usage: collect-build-resource-evidence.sh start|finish OUT.json [artifact...]' >&2; exit 64; }
mode="$1" out="$2"; shift 2
mkdir -p "$(dirname "${out}")"
MODE="${mode}" OUT="${out}" WORKSPACE_DIR="${WORKSPACE:-$PWD}" "${PYTHON_BIN:-python3}" - "$@" <<'PY'
import json, os, pathlib, shutil, subprocess, sys, time
out = pathlib.Path(os.environ['OUT']); mode = os.environ['MODE']
def read(path):
    try: return pathlib.Path(path).read_text()
    except OSError: return ''
def psi(kind):
    total = {}
    for line in read(f'/proc/pressure/{kind}').splitlines():
        parts = dict(p.split('=') for p in line.split()[1:])
        total[line.split()[0]] = {'avg10': float(parts.get('avg10', 0)), 'avg60': float(parts.get('avg60', 0)), 'total_us': int(parts.get('total', 0))}
    return total
def meminfo():
    m = {}
    for line in read('/proc/meminfo').splitlines():
        k, v = line.split(':', 1); m[k] = int(v.split()[0])
    return {k: m.get(k, 0) for k in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree')}
def diskstats():
    total_r = total_w = 0
    for line in read('/proc/diskstats').splitlines():
        f = line.split()
        if len(f) > 9 and not f[2][-1].isdigit(): total_r += int(f[5]); total_w += int(f[9])
    return {'sectors_read': total_r, 'sectors_written': total_w}
def du(path):
    try: return int(subprocess.run(['du', '-sb', path], capture_output=True, text=True, check=True).stdout.split()[0])
    except Exception: return None
ws = os.environ['WORKSPACE_DIR']
snap = {
    'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'monotonic': time.monotonic(),
    'loadavg': read('/proc/loadavg').split()[:3], 'cpuCount': os.cpu_count(),
    'mem': meminfo(), 'psi': {k: psi(k) for k in ('cpu', 'memory', 'io')}, 'disk': diskstats(),
    'df': dict(zip(('total', 'used', 'free'), shutil.disk_usage(ws))),
    'sizes': {'workspace': du(ws), 'library': du(os.path.join(ws, 'festa-unity', 'Library')), 'builds': du(os.path.join(ws, 'festa-unity', 'Builds'))},
}
if mode == 'start':
    out.write_text(json.dumps({'schemaVersion': '1.0.0', 'builderClass': 'unity-6000.0.78f1', 'start': snap}, indent=2) + '\n')
else:
    doc = json.loads(out.read_text()); start = doc['start']
    doc['finish'] = snap
    doc['durationSeconds'] = round(snap['monotonic'] - start['monotonic'], 1)
    doc['delta'] = {
        'psiTotalUs': {k: {s: snap['psi'][k][s]['total_us'] - start['psi'][k].get(s, {}).get('total_us', 0) for s in snap['psi'][k]} for k in snap['psi']},
        'sectorsRead': snap['disk']['sectors_read'] - start['disk']['sectors_read'],
        'sectorsWritten': snap['disk']['sectors_written'] - start['disk']['sectors_written'],
    }
    doc['artifacts'] = {p: os.path.getsize(p) for p in sys.argv[1:] if os.path.isfile(p)}
    out.write_text(json.dumps(doc, indent=2) + '\n')
print(f'RESOURCE_EVIDENCE_{mode.upper()}: {out}')
PY
