"""校验内部 SoC 资料及来源完整性。 / Validate internal SoC metadata and its source coverage."""
import collections
import datetime
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def normalized(value):
    return ''.join(c for c in value.lower() if c.isalnum() or c == '+')


def source(value):
    return isinstance(value, str) and value.startswith('https://')


def validate(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    assert data['schema'] == 1
    assert data['revision']
    ids = set()
    aliases = collections.defaultdict(set)
    for soc in data['socs']:
        ident = soc['id']
        assert ident not in ids, 'Duplicate ID: ' + ident
        ids.add(ident)
        assert soc['vendor'] and soc['name'] and source(soc['identity_source']), ident
        datetime.date.fromisoformat(soc['checked_at'])
        assert soc['aliases'] and soc['name'] in soc['aliases'], ident
        for alias in soc['aliases']:
            key = normalized(alias)
            assert len(key) >= 3, (ident, alias)
            aliases[key].add(ident)
        count = soc['cpu_count']
        assert count is None or isinstance(count, int) and 1 <= count <= 32, ident
        groups = soc['cpu_groups']
        if groups:
            assert count is not None and sum(g['count'] for g in groups) == count, ident
        for group in groups:
            assert 1 <= group['count'] <= 32 and group['core'], ident
            assert re.fullmatch(r'[0-9a-f]{3}', group['midr_part']) or group['midr_part'] == '', ident
            assert source(group['identity_source']), ident
            assert group['l2_scope'] in ('private', 'cluster'), ident
            line = group['cache_line_bytes']
            assert line is None or line in (32, 64, 128, 256), ident
            for key in ('l1d_bytes', 'l2_bytes'):
                value = group[key]
                if value is not None:
                    assert isinstance(value, int) and 1024 <= value <= 1073741824, (ident, key)
                    assert source(group.get('l1_source') if key == 'l1d_bytes' and group.get('l1_source') else group['cache_source']), (ident, key)
                    assert line is None or value % line == 0, ident
        l3 = soc['cpu_l3']
        if l3 is not None:
            assert l3['scope'] == 'soc' and source(l3['source']), ident
            assert isinstance(l3['bytes'], int) and 1024 <= l3['bytes'] <= 1073741824, ident
            assert l3['line_bytes'] is None or l3['line_bytes'] in (32, 64, 128, 256), ident
        slc = soc['system_cache']
        if slc is not None:
            assert slc['bytes'] > 0 and source(slc['source']), ident
        gpu = soc['gpu']
        if gpu['name'] is not None:
            assert gpu['name'] and source(gpu['source']), ident
        for key in ('shader_cores', 'slices', 'alus', 'cache_bytes', 'gmem_bytes',
                    'work_group_processors', 'shader_engines', 'render_backends'):
            value = gpu.get(key)
            assert value is None or isinstance(value, int) and value > 0 and source(gpu['source']), (ident, key)
        # 总缓存宣传值不得直接变成某一级缓存的工作集依据。
        # Advertised aggregate cache sizes must not become a specific cache-level working set.
        for cache in soc.get('advertised_cache', []):
            assert cache['level'] is None and cache['capacity'] > 0 and source(cache['source']), ident
    shared = {key: sorted(value) for key, value in aliases.items() if len(value) > 1}
    assert all(re.fullmatch(r'(?:sm\d{4}|mt\d{4}[a-z]*)', key) for key in shared), shared
    return {'revision': data['revision'], 'entries': len(ids),
            'vendors': dict(collections.Counter(s['vendor'] for s in data['socs'])),
            'shared_codes': shared,
            'unconfirmed_cpu_counts': [s['name'] for s in data['socs'] if s['cpu_count'] is None]}


if __name__ == '__main__':
    print(json.dumps(validate(ROOT / 'app/src/main/assets/soc_catalog.json'), ensure_ascii=False, indent=2))
