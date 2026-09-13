from __future__ import annotations

import math
import re
from pathlib import Path


def safe_name(name: str) -> str:
    return re.sub(r'[<>:"/\\|?*]+', "_", name).strip() or "output"


def numeric_sort_key(p: Path):
    nums = re.findall(r"\d+", p.stem)
    return (0, tuple(int(x) for x in nums), p.stem.lower()) if nums else (1, (), p.stem.lower())


def human_seconds(v: float | None) -> str:
    if v is None or not math.isfinite(v):
        return "--:--"
    s = max(0, int(round(v)))
    h, s = divmod(s, 3600)
    m, s = divmod(s, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def human_size(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    if n < 1024 ** 2:
        return f"{n / 1024:.1f} KB"
    if n < 1024 ** 3:
        return f"{n / 1024 ** 2:.1f} MB"
    return f"{n / 1024 ** 3:.2f} GB"


def inv_float(x: float) -> str:
    return f"{x:.6f}".rstrip("0").rstrip(".") or "0"
