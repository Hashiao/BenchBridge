"""从已审校的共享词库生成平台资源。 / Generate platform resources from the reviewed shared catalog."""
from pathlib import Path
import argparse,hashlib,json,re,sys
from xml.sax.saxutils import escape

ROOT=Path(__file__).resolve().parents[1]
LOCALES=('en','zh-Hans','zh-Hant')
PLACEHOLDER=re.compile(r'\{(\d+)\}|%(?:\d+\$)?[-+# 0]*(?:\d+)?(?:\.\d+)?[a-zA-Z@%]')

def legacy_template(value):
    indices=[int(v) for v in re.findall(r'\{(\d+)\}',value)]
    next_index=max(indices,default=-1)+1
    def replace(match):
        nonlocal next_index
        if match[1] is not None:return match[0]
        if match[0]=='%%':return '%'
        result='{'+str(next_index)+'}';next_index+=1;return result
    return PLACEHOLDER.sub(replace,value)

def outputs():
    catalog=json.loads((ROOT/'localization/catalog.json').read_text(encoding='utf-8'))
    for key,row in catalog.items():
        assert re.fullmatch(r'm_[a-z0-9_]+',key),key
        source=row['source']
        args=set(re.findall(r'\{\d+\}',source))
        formats=[m[0] for m in PLACEHOLDER.finditer(source) if m[1] is None]
        for locale in LOCALES:
            value=row[locale]
            assert value and set(re.findall(r'\{\d+\}',value))==args,(key,locale,'arguments')
            assert [m[0] for m in PLACEHOLDER.finditer(value) if m[1] is None]==formats,(key,locale,'printf formats')
        assert not re.search('[\u3400-\u9fff]',row['en']),(key,'untranslated English')
    generated={}
    for locale in LOCALES:
        folder={'en':'values','zh-Hans':'values-b+zh+Hans','zh-Hant':'values-b+zh+Hant'}[locale]
        lines=['<?xml version="1.0" encoding="utf-8"?>','<!-- 自动生成，请编辑 localization/catalog.json。 / Generated; edit localization/catalog.json. -->','<resources>']
        if locale=='en':lines.append('    <string name="app_name" translatable="false">BenchBridge</string>')
        for key,row in sorted(catalog.items()):
            value=row[locale].replace('\\','\\\\').replace('"','\\"').replace("'","\\'").replace('\n','\\n').replace('\t','\\t')
            lines.append(f'    <string name="{key}" formatted="false">"{escape(value)}"</string>')
        lines+=['</resources>','']
        generated[f'app/src/main/res/{folder}/strings.xml']='\n'.join(lines)
        swift=['/* 自动生成，请编辑共享词库。 / Generated; edit the shared catalog. */']
        swift += [json.dumps(key)+' = '+json.dumps(row[locale],ensure_ascii=False)+';' for key,row in sorted(catalog.items())]
        generated[f'ios/BenchBridge/{locale}.lproj/Localizable.strings']='\n'.join(swift)+'\n'
    keys=['package io.benchbridge.app.i18n','', 'import io.benchbridge.app.R','', '// 自动生成的资源索引。 / Generated resource index.', 'internal object StringResources {','    val ids = mapOf(']
    keys += [f'        "{key}" to R.string.{key},' for key in sorted(catalog)]
    keys += ['    )','}','']
    generated['app/src/main/java/io/benchbridge/app/i18n/StringResources.kt']='\n'.join(keys)
    legacy={key:{locale:legacy_template(row[locale]) for locale in LOCALES} for key,row in catalog.items()}
    payload=json.dumps(legacy,ensure_ascii=False,separators=(',',':'))+'\n'
    generated['app/src/main/assets/localization.json']=payload
    generated['ios/BenchBridge/localization.json']=payload
    return generated

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    mismatches=[]
    for relative,text in outputs().items():
        path=ROOT/relative
        if args.check:
            if not path.exists() or path.read_text(encoding='utf-8')!=text:mismatches.append(relative)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')
    if mismatches:raise SystemExit('Outdated localization resources: '+', '.join(mismatches))
    print('Localization resources checked' if args.check else 'Localization resources generated')

if __name__=='__main__':main()
