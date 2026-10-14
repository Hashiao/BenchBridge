"""云端 Mac 分发签名与上传；凭据仅放临时目录，不发布签名材料。
Archive and upload on a cloud Mac; keep credentials temporary and never publish signing material.
"""
import base64
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = "io.benchbridge.ios"


def required(name):
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError("Missing secret/input: " + name)
    return value


def main():
    if platform.system() != "Darwin":
        raise RuntimeError("Run this tool on a macOS runner with Xcode.")
    build = required("BB_STORE_BUILD_NUMBER")
    if not re.fullmatch(r"[1-9][0-9]{0,3}", build):
        raise RuntimeError("Use a new numeric build number from 1 to 9999.")
    team = required("APPLE_TEAM_ID")
    key_id = required("APPLE_API_KEY_ID")
    issuer = required("APPLE_API_ISSUER_ID")
    if not re.fullmatch(r"[A-Z0-9]{10}", team) or not re.fullmatch(r"[A-Z0-9]{10}", key_id):
        raise RuntimeError("Invalid team/key identifier format.")
    uuid.UUID(issuer)
    p12_password = required("APPLE_DISTRIBUTION_P12_PASSWORD")
    payloads = {name: base64.b64decode(required(env), validate=True) for name, env in {
        "distribution.p12": "APPLE_DISTRIBUTION_P12_BASE64",
        "profile.mobileprovision": "APPLE_PROFILE_BASE64",
        "api.p8": "APPLE_API_PRIVATE_KEY_BASE64",
    }.items()}

    # 临时目录只允许当前用户访问；不输出带密码的命令行。
    # Restrict temporary files to the current user; never log commands containing passwords.
    temp = Path(tempfile.mkdtemp(prefix="benchbridge-appstore-", dir=os.environ.get("RUNNER_TEMP")))
    os.chmod(temp, 0o700)
    keychain = temp / "signing.keychain-db"
    keychain_password = secrets.token_urlsafe(36)
    installed_profiles = []
    api_file = None
    old_keychains = None
    step = 0

    def run(args, label, capture=False):
        nonlocal step
        step += 1
        print(label, flush=True)
        log = temp / (str(step) + ".log")
        with log.open("wb") as stream:
            result = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
        output = log.read_text(errors="replace")
        if result.returncode:
            # GitHub masks configured secrets; also scrub local ephemeral passwords.
            # GitHub 会隐藏配置的 Secrets；这里也主动移除临时密码。
            tail = "\n".join(output.splitlines()[-55:])
            for value in (p12_password, keychain_password, team, key_id, issuer):
                tail = tail.replace(value, "[redacted]")
            print(tail, flush=True)
            raise RuntimeError(label + " failed")
        return output.strip() if capture else None

    try:
        candidates = [p for p in Path("/Applications").glob("Xcode*.app") if "beta" not in p.name.lower()]
        versions = [(tuple(map(int, re.findall(r"\d+", p.name))), p) for p in candidates if re.search(r"Xcode_\d", p.name)]
        accepted = [entry for entry in versions if entry[0][:2] == (27, 0)]
        # 与已验收 CI 固定到同一 Xcode 版本，不能仅按文件名排除 beta。
        # Pin the tested CI toolchain; a preview bundle may omit "beta" from its filename.
        if not accepted:
            raise RuntimeError("The tested Xcode 27.0 toolchain is not installed.")
        os.environ["DEVELOPER_DIR"] = str(max(accepted)[1] / "Contents/Developer")
        xcode = run(["xcodebuild", "-version"], "Check Xcode", capture=True)
        if not xcode.startswith("Xcode 27.0\n"):
            raise RuntimeError("This upload workflow requires the tested Xcode 27.0 toolchain.")
        for name, data in payloads.items():
            (temp / name).write_bytes(data)
            os.chmod(temp / name, 0o600)
        decoded = run(["security", "cms", "-D", "-i", str(temp / "profile.mobileprovision")], "Decode provisioning profile", capture=True)
        profile = plistlib.loads(decoded.encode())
        entitlements = profile["Entitlements"]
        if profile["TeamIdentifier"] != [team] or entitlements["application-identifier"] != team + "." + BUNDLE:
            raise RuntimeError("The profile does not match the requested team and bundle.")
        if entitlements.get("get-task-allow") or "ProvisionedDevices" in profile or profile.get("ProvisionsAllDevices"):
            raise RuntimeError("An App Store distribution profile is required.")
        uuid.UUID(profile["UUID"])
        profile_uuid = profile["UUID"]
        # 同时兼容 Xcode 的两种描述文件目录；仅清理本次创建的文件。
        # Support both Xcode profile locations; clean only files created by this run.
        for relative in ("Library/MobileDevice/Provisioning Profiles", "Library/Developer/Xcode/UserData/Provisioning Profiles"):
            profile_dir = Path.home() / relative
            profile_dir.mkdir(parents=True, exist_ok=True)
            candidate = profile_dir / (profile_uuid + ".mobileprovision")
            if candidate.exists():
                raise RuntimeError("Refusing to replace an existing runner profile.")
            shutil.copyfile(temp / "profile.mobileprovision", candidate)
            installed_profiles.append(candidate)

        old_keychains = shlex.split(run(["security", "list-keychains", "-d", "user"], "Read runner keychain list", capture=True))
        run(["security", "create-keychain", "-p", keychain_password, str(keychain)], "Create temporary signing keychain")
        run(["security", "set-keychain-settings", "-lut", "21600", str(keychain)], "Set keychain timeout")
        run(["security", "unlock-keychain", "-p", keychain_password, str(keychain)], "Unlock temporary keychain")
        run(["security", "import", str(temp / "distribution.p12"), "-k", str(keychain), "-P", p12_password,
             "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"], "Import distribution signing identity")
        run(["security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", keychain_password, str(keychain)], "Allow codesign access")
        run(["security", "list-keychains", "-d", "user", "-s", str(keychain), *old_keychains], "Add temporary keychain to search list")

        metal = subprocess.run(["xcrun", "-sdk", "iphoneos", "metal", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if metal.returncode:
            run(["xcodebuild", "-downloadComponent", "MetalToolchain"], "Install Apple Metal toolchain")
        run([sys.executable, "tools/check-localization.py"], "Validate all localizations")
        run([sys.executable, "tools/generate-apple-project.py"], "Generate the Xcode project")
        info_path = ROOT / "ios/BenchBridge/Info.plist"
        info = plistlib.loads(info_path.read_bytes())
        version = info["CFBundleShortVersionString"]
        info["CFBundleVersion"] = build
        info_path.write_bytes(plistlib.dumps(info))
        archive = temp / "BenchBridge.xcarchive"
        run(["xcodebuild", "-project", "ios/BenchBridge.xcodeproj", "-scheme", "BenchBridge", "-configuration", "Release",
             "-destination", "generic/platform=iOS", "-archivePath", str(archive), "archive", "CODE_SIGN_STYLE=Manual",
             "DEVELOPMENT_TEAM=" + team, "CODE_SIGN_IDENTITY=Apple Distribution", "PROVISIONING_PROFILE_SPECIFIER=" + profile["Name"]], "Archive signed iPhone/iPad build")
        app = archive / "Products/Applications/BenchBridge.app"
        archived_info = plistlib.loads((app / "Info.plist").read_bytes())
        if (archived_info["CFBundleIdentifier"], archived_info["CFBundleShortVersionString"], archived_info["CFBundleVersion"]) != (BUNDLE, version, build):
            raise RuntimeError("Archive identity/version mismatch.")
        run(["codesign", "--verify", "--deep", "--strict", str(app)], "Verify the archive signature")
        export_options = temp / "ExportOptions.plist"
        export_options.write_bytes(plistlib.dumps({"method": "app-store-connect", "destination": "export", "teamID": team,
            "signingStyle": "manual", "signingCertificate": "Apple Distribution", "provisioningProfiles": {BUNDLE: profile["Name"]}, "uploadSymbols": True}))
        exported = temp / "export"
        run(["xcodebuild", "-exportArchive", "-archivePath", str(archive), "-exportOptionsPlist", str(export_options), "-exportPath", str(exported)], "Export App Store package")
        ipas = list(exported.glob("*.ipa"))
        if len(ipas) != 1:
            raise RuntimeError("Expected exactly one exported IPA.")
        api_directory = Path.home() / ".appstoreconnect/private_keys"
        api_directory.mkdir(parents=True, exist_ok=True)
        api_candidate = api_directory / ("AuthKey_" + key_id + ".p8")
        if api_candidate.exists():
            raise RuntimeError("Refusing to replace an existing runner API key.")
        shutil.copyfile(temp / "api.p8", api_candidate)
        api_file = api_candidate
        os.chmod(api_file, 0o600)
        auth = ["--apiKey", key_id, "--apiIssuer", issuer]
        run(["xcrun", "altool", "--validate-app", "-f", str(ipas[0]), "-t", "ios", *auth], "Validate package with App Store Connect")
        run(["xcrun", "altool", "--upload-app", "-f", str(ipas[0]), "-t", "ios", *auth], "Upload package to App Store Connect")
        summary = {"bundle_id": BUNDLE, "version": version, "build": build, "commit": os.environ.get("GITHUB_SHA"),
                   "xcode": xcode, "status": "uploaded_pending_apple_processing", "submitted_for_review": False}
        print(json.dumps(summary), flush=True)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
                stream.write(f"BenchBridge {version} ({build}) uploaded. Apple processing and review are separate steps.\n")
    finally:
        # 清理仅限本次创建的凭据；不上传密钥、描述文件、归档或签名 IPA 为公开附件。
        # Clean only credentials created here; do not publish keys, profiles, archives or signed IPAs as artifacts.
        if api_file and api_file.exists():
            api_file.unlink()
        for installed_profile in installed_profiles:
            if installed_profile.exists():
                installed_profile.unlink()
        if old_keychains is not None:
            subprocess.run(["security", "list-keychains", "-d", "user", "-s", *old_keychains], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if keychain.exists():
            subprocess.run(["security", "delete-keychain", str(keychain)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.rmtree(temp)


if __name__ == "__main__":
    main()
