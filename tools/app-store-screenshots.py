"""为商店截取大屏 iPhone/iPad 的真实界面，不生成或修改跑分。
Capture actual large-screen iPhone/iPad UI for the store, without generating or altering scores.
"""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".local/app-store-screenshots"


def main():
    if platform.system() != "Darwin":
        raise RuntimeError("Requires macOS/Xcode.")
    OUT.mkdir(parents=True, exist_ok=True)
    build = ROOT / ".local/store-screenshot-build"
    versions = [(tuple(map(int, re.findall(r"\d+", p.name))), p) for p in Path("/Applications").glob("Xcode*.app")
                if re.search(r"Xcode_\d", p.name) and "beta" not in p.name.lower()]
    accepted = [v for v in versions if v[0][0] >= 27]
    if accepted:
        os.environ["DEVELOPER_DIR"] = str(max(accepted)[1] / "Contents/Developer")

    def run(args, label, capture=False):
        print(label, flush=True)
        with (OUT / (label + ".log")).open("w") as stream:
            result = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
        value = (OUT / (label + ".log")).read_text(errors="replace")
        if result.returncode:
            print("\n".join(value.splitlines()[-50:]), flush=True)
            raise RuntimeError(label + " failed")
        return value.strip() if capture else None

    manifest = {"commit": os.environ.get("GITHUB_SHA"), "status": "running", "screenshots": [], "devices": []}
    def save():
        (OUT / "verification.json").write_text(json.dumps(manifest, indent=2) + "\n")
    save()
    try:
        manifest["xcode"] = run(["xcodebuild", "-version"], "xcode", True)
        run([sys.executable, "tools/check-localization.py"], "localization")
        run([sys.executable, "tools/generate-apple-project.py"], "project")
        metal = subprocess.run(["xcrun", "-sdk", "iphonesimulator", "metal", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if metal.returncode:
            run(["xcodebuild", "-downloadComponent", "MetalToolchain"], "metal")
        devices = json.loads(run(["xcrun", "simctl", "list", "devices", "available", "-j"], "devices", True))["devices"]
        for family in ("iPhone", "iPad"):
            options = [(runtime, device) for runtime, values in devices.items() if "iOS-27-" in runtime for device in values
                       if device.get("isAvailable") and (("iPhone" in device["name"] and "Pro Max" in device["name"]) if family == "iPhone"
                       else ("iPad Pro" in device["name"] and ("13-inch" in device["name"] or "12.9-inch" in device["name"])))]
            if not options:
                raise RuntimeError("No large-screen " + family + " simulator available; do not scale smaller captures.")
            # 优先使用商店已支持的标准大屏尺寸；选择过程与实际设备均写入记录。
            # Prefer established large-screen dimensions and record the actual simulator selection.
            preferred = [x for x in options if ("iPhone 16 Pro Max" in x[1]["name"] if family == "iPhone" else "13-inch" in x[1]["name"] and "M4" in x[1]["name"])]
            runtime, device = max(preferred or options, key=lambda x: (x[0], x[1]["name"]))
            udid = device["udid"]
            manifest["devices"].append({"family": family, "name": device["name"], "runtime": runtime})
            save()
            if device.get("state") != "Booted":
                run(["xcrun", "simctl", "boot", udid], family + "-boot")
            run(["xcrun", "simctl", "bootstatus", udid, "-b"], family + "-ready")
            run(["xcrun", "simctl", "status_bar", udid, "override", "--time", "09:41", "--dataNetwork", "wifi", "--wifiMode", "active", "--wifiBars", "3", "--batteryState", "charged", "--batteryLevel", "100"], family + "-status-bar")
            result = ROOT / (".local/store-" + family + ".xcresult")
            attachments = OUT / (family + "-attachments")
            try:
                run(["xcodebuild", "-project", "ios/BenchBridge.xcodeproj", "-scheme", "BenchBridge", "-configuration", "Release",
                     "CODE_SIGNING_ALLOWED=NO", "-destination", "id=" + udid, "-derivedDataPath", str(build),
                     "-resultBundlePath", str(result), "-parallel-testing-enabled", "NO",
                     "-only-testing:BenchBridgeUITests/BenchBridgeUITests/testAutomaticLanguagesAndEnglishFallback", "test"], family + "-capture")
                run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result), "--output-path", str(attachments)], family + "-export")
                index = json.loads((attachments / "manifest.json").read_text())
                for locale in ("en", "zh-Hans", "zh-Hant"):
                    for order, title in enumerate(("Locale", "Settings", "Device"), 1):
                        matches = [a for entry in index for a in entry.get("attachments", []) if a.get("suggestedHumanReadableName", "").startswith(title + " " + locale + "_")]
                        if len(matches) != 1 or matches[0].get("isAssociatedWithFailure"):
                            raise RuntimeError("Expected one successful " + family + " " + title + " " + locale + " capture")
                        source = attachments / matches[0]["exportedFileName"]
                        target = OUT / locale / (family + "-" + str(order) + "-" + title + ".png")
                        target.parent.mkdir(exist_ok=True)
                        shutil.copyfile(source, target)
                        manifest["screenshots"].append(str(target.relative_to(OUT)))
            finally:
                subprocess.run(["xcrun", "simctl", "shutdown", udid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            save()
        manifest["status"] = "passed"
    finally:
        save()
        if build.exists():
            shutil.rmtree(build)


if __name__ == "__main__":
    main()
