"""验证三语资源及源代码引用。 / Validate three-language resources and source references."""
from pathlib import Path
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
subprocess.run([sys.executable, str(ROOT / "tools/generate-localizations.py"), "--check"], check=True)
catalog = json.loads((ROOT / "localization/catalog.json").read_text(encoding="utf-8"))
errors = []
used = set()
# 忽略注释；这里只防止中文字符串回流，英文新文案仍需人工审查。
# Ignore comments; this guard catches inline Chinese, while new English copy still needs review.
tokens = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
for directory, extension in [("app/src/main/java", "*.kt"), ("ios/BenchBridge", "*.swift")]:
    for path in (ROOT / directory).rglob(extension):
        if path.name == "StringResources.kt":
            continue
        source = path.read_text(encoding="utf-8")
        for token in tokens.finditer(source):
            value = token[0]
            if value.startswith(("//", "/*")):
                continue
            used.update(re.findall(r'\bm_[0-9a-f]{12}\b', value))
            if re.search(r'[\u3400-\u9fff]', value):
                line = source.count("\n", 0, token.start()) + 1
                errors.append(f"{path.relative_to(ROOT)}:{line}: inline Chinese; use the shared catalog")
for key in sorted(used - catalog.keys()):
    errors.append(f"Missing key: {key}")
for key, row in catalog.items():
    for term in ("內存", "緩存", "線程", "帶寬", "磁盤", "後臺", "調度", "採樣", "噪聲", "影象"):
        if term in row["zh-Hant"]:
            errors.append(f"{key}: review Traditional Chinese terminology: {term}")
for relative in ("AGENTS.md", "README.md", "CONTRIBUTING.md", "docs/LOCALIZATION.md"):
    if not (ROOT / relative).is_file():
        errors.append(f"Missing language policy/documentation: {relative}")
if errors:
    raise SystemExit("\n".join(errors))
print(f"Localization checks passed: {len(catalog)} entries, three complete locales, {len(used)} referenced keys")
