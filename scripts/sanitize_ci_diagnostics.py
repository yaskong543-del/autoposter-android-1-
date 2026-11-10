#!/usr/bin/env python3
"""Redact and validate CI diagnostics before publishing them."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
allowed = {".txt", ".xml", ".log"}
patterns = [
    (re.compile(r"(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]+=*"), r"\\1[REDACTED]"),
    (re.compile(r"(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/-]{20,}"), r"\\1[REDACTED]"),
    (re.compile(r"(?i)(sk-ant-[A-Za-z0-9_-]{10,})"), "[REDACTED_PROVIDER_KEY]"),
    (re.compile(r"(?i)(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})"), "[REDACTED_JWT]"),
    (re.compile(r"""(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|service[_-]?role[_-]?key|password)\\s*["']?\\s*[:=]\\s*["']?)[^\\s"',;<>]{8,}"""), r"\\1[REDACTED]"),
    (re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9_-]{80,}(?![A-Za-z0-9])"), "[REDACTED_LONG_SECRET_LIKE_VALUE]"),
]
remaining = [
    re.compile(r"(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{20,}"),
    re.compile(r"sk-ant-[A-Za-z0-9_-]{10,}"),
    re.compile(r"eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    re.compile(r"(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|service[_-]?role[_-]?key|password)\\s*[:=]\\s*[^\\s<]{8,}"),
    re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9_-]{80,}(?![A-Za-z0-9])"),
]
for path in root.rglob("*"):
    if path.is_symlink():
        raise SystemExit(f"Refusing symlink in diagnostics: {path}")
    if not path.is_file():
        continue
    if path.suffix.lower() not in allowed or path.stat().st_size > 2 * 1024 * 1024:
        path.unlink()
        continue
    try:
        data = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        path.unlink()
        continue
    for pattern, replacement in patterns:
        data = pattern.sub(replacement, data)
    for pattern in remaining:
        if pattern.search(data):
            raise SystemExit(f"Credential-like content remains in {path}; refusing to publish")
    path.write_text(data, encoding="utf-8")
