"""Read the v6 status without loading a model."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent / "runs/fily-support-v6-4h"
for name in ["pipeline.json", "progress.json", "failure.json"]:
    path = root / name
    if path.exists():
        value = json.loads(path.read_text(encoding="utf-8"))
        keys = ["status", "pid", "step", "loss", "elapsed_seconds", "remaining_seconds", "eta_utc", "selected_adapter", "export", "error"]
        print(name + ": " + json.dumps({key: value[key] for key in keys if key in value}, indent=2))
