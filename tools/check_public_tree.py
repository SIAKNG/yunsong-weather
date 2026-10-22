"""Check the files eligible for Git upload without echoing matched secrets."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
).decode("utf-8").split("\0")
rules = {
    "private-key-material": rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=]{32}",
    "github-token": rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})",
    "personal-machine-path": rb"(?:[A-Z]:[/\\]+Users[/\\]+(?!Public\b|example\b|USER\b)[^\s/\\]+|/(?:Users|home)/[^\s/]+)",
}
violations = []
for name in sorted(set(paths) - {""}):
    path = root / name
    if not path.is_file():
        continue
    if (name.startswith(("app/src/full/", "app/src/main/assets/weather/", "app/src/main/assets/life_advice/")) or path.suffix.lower() in
        {".apk", ".aab", ".jks", ".keystore", ".pem", ".pfx", ".p12", ".log", ".db"}
        or path.name in {"local.properties", ".env"}):
        violations.append((name, "private-file"))
    if path.suffix.lower() in {".jar", ".png", ".jpg", ".webp"}:
        continue
    data = path.read_bytes()
    for label, pattern in rules.items():
        if re.search(pattern, data, re.I):
            violations.append((name, label))
for name, label in violations:
    print(f"BLOCKED {name}: {label}")
if violations:
    sys.exit(1)
print(f"Public tree checks passed ({len(set(paths) - {''})} files); this is not a complete security or rights audit.")
