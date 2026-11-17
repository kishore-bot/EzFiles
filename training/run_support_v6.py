"""Four-hour CUDA continuation; previous jobs and models are never overwritten."""
import argparse
import ctypes
import hashlib
import json
import os
import shutil
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path
from run_five_hour import ROOT, job_lock, run, write

OUTPUT = ROOT / "runs/fily-support-v6-4h"
EXPORT = ROOT / "exports/fily-support-v6-4h"
DATA = ROOT / "data/support_v6"
BUDGET = 14400


def relative(path): return str(path.relative_to(ROOT))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (OUTPUT / "completed.json").exists(): raise FileExistsError("The completed run is preserved.")
    if shutil.disk_usage(ROOT).free < 8 * 1024 ** 3: raise RuntimeError("At least 8 GiB of free storage is required.")
    data_manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    for split, info in data_manifest["splits"].items():
        assert hashlib.sha256((DATA / f"{split}.jsonl").read_bytes()).hexdigest() == info["sha256"], "Dataset changed"
    best = json.loads((ROOT / "runs/fily-edge-v5-5h/best.json").read_text())
    state = {"status": "training", "pid": os.getpid(), "budget_seconds": BUDGET,
             "started_utc": datetime.now(timezone.utc).isoformat(), "initial_adapter": best["adapter"],
             "application_source_changed_by_this_job": False, "dataset": relative(DATA), "output": relative(OUTPUT)}
    with job_lock(OUTPUT / "worker.lock"):
        write(OUTPUT / "pipeline.json", state)
        try:
            if os.name == "nt": ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
            if not (OUTPUT / "report.json").exists():
                run("train_long.py", "--output", relative(OUTPUT), "--seconds", BUDGET,
                    "--init-adapter", best["adapter"], "--train-file", relative(DATA / "train.jsonl"),
                    "--validation-file", relative(DATA / "validation.jsonl"), "--max-length", 1536,
                    "--validation-samples", 384, "--learning-rate", "0.00002", "--interval", 900,
                    *(["--resume"] if args.resume else []))
            report = json.loads((OUTPUT / "report.json").read_text())
            if report["status"] != "completed":
                write(OUTPUT / "pipeline.json", {**state, "status": report["status"], "training": report}); return
            selected = report["selected_adapter"]
            state.update(status="exporting", selected_adapter=selected, export=relative(EXPORT))
            write(OUTPUT / "pipeline.json", state)
            if not (EXPORT / "manifest.json").exists():
                run("export_quantized.py", "--adapter", selected, "--output", relative(EXPORT))
            else:
                assert json.loads((EXPORT / "manifest.json").read_text())["adapter"] == selected
            for name in ["tools.json", "policy.txt", "manifest.json", "capabilities.json"]:
                shutil.copy2(DATA / name, EXPORT / ("dataset_manifest.json" if name == "manifest.json" else name))
            shutil.copy2(DATA / "capabilities.json", EXPORT / "app_capabilities.json")
            shutil.copy2(ROOT / "APP_SUPPORT_V6.md", EXPORT / "APP_SUPPORT_V6.md")
            state["status"] = "calibrating"; write(OUTPUT / "pipeline.json", state)
            if not (EXPORT / "calibration-manifest.json").exists():
                run("calibrate_quantization.py", "--export", relative(EXPORT), "--train-file", relative(DATA / "train.jsonl"))
            state["status"] = "evaluating"; write(OUTPUT / "pipeline.json", state)
            baseline = "exports/fily-edge-v5-5h/Fily-EzFiles-350M-Q8_0.gguf"
            current = relative(EXPORT / "Fily-EzFiles-350M-Q8_0.gguf")
            compact = relative(EXPORT / "Fily-EzFiles-350M-Q4_K_M-I.gguf")
            evaluations = []
            tests = [("before-support", baseline, "support_v6/test", 160, False),
                     ("after-support", current, "support_v6/test", 160, False),
                     ("compact-support", compact, "support_v6/test", 160, False),
                     ("before-free-support", baseline, "support_v6/test", 80, True),
                     ("after-free-support", current, "support_v6/test", 80, True),
                     ("before-acceptance", baseline, "support_v6/acceptance", 64, True),
                     ("after-acceptance", current, "support_v6/acceptance", 64, True),
                     ("compact-acceptance", compact, "support_v6/acceptance", 64, True),
                     ("before-retention", baseline, "edge_v5/test", 128, False),
                     ("after-retention", current, "edge_v5/test", 128, False)]
            for label, model, suite, limit, unconstrained in tests:
                path = EXPORT / f"evaluation-{label}.json"
                if not path.exists():
                    run("evaluate_support_v6.py", "--model", model, "--suite", suite,
                        "--limit", limit, "--output", relative(path), *(["--unconstrained"] if unconstrained else []))
                value = json.loads(path.read_text())
                evaluations.append({"label": label, **{k: v for k, v in value.items() if k != "results"}})
            state["status"] = "benchmarking"; write(OUTPUT / "pipeline.json", state)
            run("bench_quantized.py", "--export", relative(EXPORT))
            final = {**state, "status": "completed", "completed_utc": datetime.now(timezone.utc).isoformat(),
                     "training": report, "evaluations": evaluations,
                     "quality_gate": "Review held-out semantic failures and retention before promotion. No automatic model replacement.",
                     "limits": "Synthetic text tests, not microphone, phone, file execution, factuality or complete edge-case certification."}
            write(OUTPUT / "completed.json", final); write(OUTPUT / "pipeline.json", final)
            lines = ["# Four-hour Fily app-support run completed", "", f"Selected adapter: {selected}",
                     f"Training steps: {report['steps']}; loop seconds: {report['elapsed_seconds']:.1f}", "",
                     "| Test | Examples | Valid contract | Exact tools | Exact responses |", "|---|---:|---:|---:|---:|"]
            for item in evaluations:
                lines.append(f"| {item['label']} | {item['examples']} | {item['contract_valid']} | {item['tool_exact_matches']}/{item['tool_examples']} | {item['exact_matches']} |")
            lines += ["", final["quality_gate"], final["limits"]]
            (OUTPUT / "RUN_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
            with (ROOT.parent / "NOTES.md").open("a", encoding="utf-8") as notes:
                notes.write(f"\n\n## Automated Fily v6 completion: {final['completed_utc']}\n"
                            f"- Owner-authorized four-hour CUDA job completed {report['steps']} steps in {report['elapsed_seconds']:.1f} loop seconds. Selected {selected}.\n"
                            f"- Results: {relative(OUTPUT)}/RUN_REPORT.md and {relative(EXPORT)}/evaluation-*.json. No app source, installed model or phone behavior was changed.\n"
                            "- Inspect semantic quality and retention before selecting a model. Training completion alone is not an improvement claim.\n")
            print(json.dumps({"status": "completed", "report": relative(OUTPUT / "RUN_REPORT.md")}), flush=True)
        except BaseException as error:
            write(OUTPUT / "pipeline.json", {**state, "status": "failed", "error": f"{type(error).__name__}: {error}",
                  "traceback": traceback.format_exc(), "resume_command": "training/.venv/Scripts/python.exe training/run_support_v6.py --resume"})
            raise
        finally:
            if os.name == "nt": ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)


if __name__ == "__main__": main()
