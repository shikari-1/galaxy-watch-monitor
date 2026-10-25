#!/usr/bin/env python3
"""
Galaxy Watch 4 - GitHub Actions Artifact Downloader
===================================================
Uses local Git credentials to authenticate with GitHub's REST API and
download the latest compiled Wear OS APK artifact directly into wear-app/.
"""

import os
import sys
import io
import json
import zipfile
import subprocess
import urllib.request

REPO = "shikari-1/galaxy-watch-monitor"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DEST_DIR = os.path.join(PROJECT_ROOT, "wear-app")

def get_git_token():
    """Extract stored GitHub OAuth/PAT token from Git Credential Manager."""
    try:
        p = subprocess.Popen(
            ["git", "credential", "fill"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        out, _ = p.communicate("protocol=https\nhost=github.com\n\n", timeout=5)
        for line in out.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception as e:
        print(f"[-] Could not query git credentials: {e}")
    return None

def download_latest_artifact():
    print("========================================================")
    print("   Galaxy Watch 4 - GitHub Actions Artifact Downloader   ")
    print("========================================================")
    
    token = get_git_token()
    headers = {"User-Agent": "GW4-Artifact-Downloader"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
        print("[+] Authenticated using local Git Credential Manager.")
    else:
        print("[!] No git credential token found; attempting request...")

    # 1. Fetch latest successful workflow run
    runs_url = f"https://api.github.com/repos/{REPO}/actions/runs?status=completed&conclusion=success"
    print(f"[*] Querying latest successful run from: {REPO}...")
    req = urllib.request.Request(runs_url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        runs_data = json.loads(resp.read().decode("utf-8"))
        runs = runs_data.get("workflow_runs", [])
        if not runs:
            print("[-] No successful workflow runs found.")
            return False

    latest_run = runs[0]
    run_id = latest_run["id"]
    print(f"[+] Found latest successful Run #{run_id} ({latest_run.get('head_commit', {}).get('message', '').strip()})")

    # 2. Get artifact list for this run
    artifacts_url = f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts"
    req_art = urllib.request.Request(artifacts_url, headers=headers)
    with urllib.request.urlopen(req_art, timeout=15) as resp:
        artifacts_data = json.loads(resp.read().decode("utf-8"))
        artifacts = artifacts_data.get("artifacts", [])
        
    target_art = None
    for a in artifacts:
        if a["name"] == "wear-app-debug":
            target_art = a
            break

    if not target_art:
        print("[-] 'wear-app-debug' artifact not found in this run.")
        return False

    art_id = target_art["id"]
    size_mb = target_art["size_in_bytes"] / (1024 * 1024)
    dl_url = target_art["archive_download_url"]
    print(f"[+] Found artifact ID: {art_id} ({size_mb:.2f} MB)")
    print(f"[*] Downloading artifact zip from GitHub Actions...")

    # 3. Request artifact download URL (intercept 302 redirect so Auth header isn't sent to S3/Azure)
    class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, hdrs, newurl):
            return None

    dl_req = urllib.request.Request(dl_url, headers=headers)
    opener = urllib.request.build_opener(NoRedirectHandler)
    storage_url = None
    try:
        opener.open(dl_req, timeout=15)
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 307, 308):
            storage_url = e.headers.get("Location")

    if not storage_url:
        print("[-] Could not retrieve pre-signed storage URL.")
        return False

    print(f"[+] Retrieved pre-signed storage URL. Downloading payload...")
    storage_req = urllib.request.Request(storage_url, headers={"User-Agent": "GW4-Artifact-Downloader"})
    with urllib.request.urlopen(storage_req, timeout=60) as resp:
        zip_bytes = resp.read()

    print(f"[+] Download complete ({len(zip_bytes) / (1024 * 1024):.2f} MB received).")
    print(f"[*] Extracting app-debug.apk into {DEST_DIR}...")
    
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
        z.extractall(DEST_DIR)

    extracted_apk = os.path.join(DEST_DIR, "app-debug.apk")
    if os.path.isfile(extracted_apk):
        print(f"[SUCCESS] {extracted_apk} is ready! ({os.path.getsize(extracted_apk) / (1024 * 1024):.2f} MB)")
        return True
    else:
        print("[-] app-debug.apk was not found inside the zip archive.")
        return False

if __name__ == "__main__":
    success = download_latest_artifact()
    sys.exit(0 if success else 1)
