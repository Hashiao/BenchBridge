"""发布已验证的签名 APK 与可选附件；不构建、不改标签。 / Publish a verified signed APK and optional assets without building or moving tags."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
NAME = "BenchBridge-release.apk"


def git(*args, **kwargs):
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, encoding="utf-8", **kwargs)
    if result.returncode:
        raise RuntimeError("Git command failed: " + args[0])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--asset", type=Path, action="append", default=[], help="Additional verified release file (repeatable) / 额外已验证附件，可重复")
    parser.add_argument("--proxy", help="Optional HTTPS proxy URL / 可选 HTTPS 代理地址")
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
        raise RuntimeError("Use an explicit stable version tag, e.g. v0.5.0")
    if git("status", "--porcelain"):
        raise RuntimeError("Commit all source changes before publishing")
    head = git("rev-parse", "HEAD")
    if git("rev-parse", args.tag + "^{commit}") != head:
        raise RuntimeError("The tag must identify the checked-out commit")
    remote = git("remote", "get-url", "origin")
    match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:)([\w.-]+/[\w.-]+?)(?:\.git)?", remote)
    if not match:
        raise RuntimeError("origin must be a GitHub repository without embedded credentials")
    repo = match[1]
    refs = dict(line.split()[::-1] for line in git("ls-remote", "origin", "refs/tags/" + args.tag,
                                                "refs/tags/" + args.tag + "^{}").splitlines())
    if refs.get("refs/tags/" + args.tag + "^{}", refs.get("refs/tags/" + args.tag)) != head:
        raise RuntimeError("Push the matching tag before publishing")
    metadata = json.loads((args.apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    if metadata.get("applicationId") != "io.benchbridge.app" or not any(
        entry.get("versionName") == args.tag[1:] and entry.get("outputFile") == args.apk.name
        for entry in metadata.get("elements", [])
    ):
        raise RuntimeError("APK package/version does not match the release tag")
    payload = args.apk.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != args.sha256.lower():
        raise RuntimeError("APK differs from the verified SHA-256")
    files = [(NAME, payload, "application/vnd.android.package-archive"),
             (NAME + ".sha256", (digest + "  " + NAME + "\n").encode(), "text/plain")]
    names = {item[0] for item in files}
    for path in args.asset:
        name = path.name
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) or name in names or name + ".sha256" in names:
            raise RuntimeError("Invalid or duplicate release asset: " + name)
        data = path.read_bytes()
        files.extend([(name, data, "application/octet-stream"),
                      (name + ".sha256", (hashlib.sha256(data).hexdigest() + "  " + name + "\n").encode(), "text/plain")])
        names.update((name, name + ".sha256"))
    notes = args.notes_file.read_text(encoding="utf-8")
    # 凭据只留在内存中；不写文件、不放进命令行或日志。
    # Keep credentials in memory, never in files, command arguments or logs.
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        response = git("credential", "fill", input="protocol=https\nhost=github.com\npath=" + repo + ".git\n\n",
                       env={**os.environ, "GCM_INTERACTIVE": "Never", "GIT_TERMINAL_PROMPT": "0"})
        token = dict(line.split("=", 1) for line in response.splitlines() if "=" in line).get("password")
    if not token:
        raise RuntimeError("GitHub authentication is required")
    handlers = [urllib.request.ProxyHandler({"https": args.proxy})] if args.proxy else []
    # API 请求禁止重定向，避免把认证头转发到其他主机。
    # API calls reject redirects so authentication headers cannot reach another host.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    api = urllib.request.build_opener(*handlers, NoRedirect())
    public = urllib.request.build_opener(*handlers)

    def request(url, method="GET", data=None, content_type="application/json"):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "uploads.github.com"}:
            raise RuntimeError("Unexpected GitHub API host")
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + token, "User-Agent": "BenchBridge-release",
            "Accept": "application/vnd.github+json", "Content-Type": content_type,
            "X-GitHub-Api-Version": "2022-11-28"})
        with api.open(req, timeout=120) as result:
            return json.load(result)

    base = "https://api.github.com/repos/" + repo
    releases = request(base + "/releases?per_page=100")
    release = next((item for item in releases if item["tag_name"] == args.tag), None)
    if release is None:
        release = request(base + "/releases", "POST", dict(tag_name=args.tag, target_commitish=head,
                          name="BenchBridge " + args.tag[1:], body=notes, draft=True, prerelease=False))
    # 草稿可重试；已发布附件只能核对，不能替换。
    # Drafts may be resumed; published assets are verified, never replaced.
    for name, data, mime in files:
        asset = next((a for a in release["assets"] if a["name"] == name), None)
        if asset is None:
            if not release["draft"]:
                raise RuntimeError("Published release is missing an asset; create a new version")
            asset = request(release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name), "POST", data, mime)
        expected = "sha256:" + hashlib.sha256(data).hexdigest()
        if asset.get("digest") != expected or asset.get("size") != len(data):
            raise RuntimeError("Uploaded asset digest/size mismatch: " + name)
    if release["draft"]:
        release = request(base + "/releases/" + str(release["id"]), "PATCH", dict(body=notes, draft=False, make_latest="true"))
    url = "https://github.com/" + repo + "/releases/latest/download/" + NAME
    # 公开下载链路不携带认证信息，并重新校验最终文件。
    # Verify the public download path without sending authentication.
    with public.open(urllib.request.Request(url, headers={"User-Agent": "BenchBridge-release-check"}), timeout=120) as result:
        downloaded = hashlib.file_digest(result, "sha256").hexdigest()
    if downloaded != digest:
        raise RuntimeError("Latest-download URL does not serve the verified APK")
    for name, data, _ in files[2:]:
        asset_url = "https://github.com/" + repo + "/releases/download/" + args.tag + "/" + name
        with public.open(urllib.request.Request(asset_url, headers={"User-Agent": "BenchBridge-release-check"}), timeout=120) as result:
            if hashlib.file_digest(result, "sha256").hexdigest() != hashlib.sha256(data).hexdigest():
                raise RuntimeError("Public asset download mismatch: " + name)
    print(json.dumps(dict(release=release["html_url"], download=url, sha256=digest)))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        sys.exit("GitHub HTTP " + str(error.code) + "; no credentials or response body logged")
    except (OSError, RuntimeError, ValueError) as error:
        sys.exit(str(error))
