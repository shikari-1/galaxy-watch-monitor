import urllib.request
import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from download_artifact import get_git_token

token = get_git_token()
headers = {"User-Agent": "GW4-Checker"}
if token:
    headers["Authorization"] = f"Bearer {token}"

url = "https://api.github.com/repos/shikari-1/galaxy-watch-monitor/actions/runs?per_page=6"
req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        for run in data.get("workflow_runs", []):
            commit_msg = run.get("head_commit", {}).get("message", "").splitlines()[0]
            print(f"Run #{run['id']}: status={run['status']}, conclusion={run['conclusion']} | Commit: '{commit_msg}'")
except Exception as e:
    print(f"Error querying GitHub: {e}")
