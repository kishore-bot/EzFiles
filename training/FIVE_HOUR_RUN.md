# Expanded edge curriculum and five-hour run

The owner requested a larger app-specific edge-case dataset and five hours of local GPU training. Application source is unchanged. Product ideas and their supporting app work are in [APP_INTELLIGENCE_PLAN.md](APP_INTELLIGENCE_PLAN.md).

The prepared synthetic curriculum has 46,987 training conversations / 63,587 assistant targets, 1,887 validation conversations / 2,551 targets, and 1,882 test conversations / 2,546 targets. It covers 250 authored scenario families and all 37 current training action contracts. Builders validate every row and remove exact conversation duplicates across splits. This does not remove shared semantic templates or prove coverage of every possible edge case. No personal document was read.

Coverage includes date/ownership/name ambiguity; incomplete indexes; wrong/stale/missing/provider references; copied dates; extracted-versus-unavailable text; passwords; conflicting clauses and revisions; malicious document instructions; ordered selections; invalid page bounds; unsaved changes; save/share/print state; cancellation versus additional requests; codec/track limits; similarity versus duplicates; review-only filing; reset conversation context; preference opt-out; exact dictation; document comparison; and app scope. Future reminders, relationship indexing and editing adapters are explained as proposals/limits, not invented working APIs.

## Current files

- `data/edge_v5/manifest.json`: family/tool/tag counts, hashes, split limitations.
- `runs/fily-edge-v5-5h/progress.json`: live stage, steps, loss, remaining time and ETA.
- `runs/fily-edge-v5-5h/pipeline.json`: supervisor PID and stage.
- `runs/fily-edge-v5-5h/metrics.jsonl`: per-step metrics.
- `runs/fily-edge-v5-5h/latest.json` and `best.json`: complete checkpoint pointers.
- `runs/fily-edge-v5-5h/completed.json` and `RUN_REPORT.md`: final results, only after completion.
- `exports/fily-edge-v5-5h/`: automatically exported/quantized/evaluated models after training.

The token cache is compact int32 data with memory-mapped indices. It preserves the entire prompt and target and refuses overlong examples. Only assistant target tokens contribute loss. The longest examples passed a real CUDA smoke check on the RTX 2050; optimizer/RNG/sampler checkpoint recovery was exercised in a separate run. The actual long job starts from the preserved v4 adapter, not the smoke model.

## Training contract

The normal worker runs a 18,000-second training-loop budget, accumulation four, batch size one, learning rate 3e-5, rank-8 continuation, gradient checkpointing and supported CUDA mixed precision. Cache preparation and initial model/validation setup are outside that budget. Periodic validation and checkpoint writes are inside the budget; final validation/export/evaluation take additional time. Checkpoints are saved about every 15 minutes, with best checkpoint selected by fixed stratified validation loss. More elapsed GPU time is not a quality guarantee. The test set is used after training, not to choose checkpoints.

The supervisor prevents duplicate workers, requests that Windows remain awake during the job without forcing the display on, and releases that request on exit. It does not prevent power-off or intentional lid/sleep actions. Keep the laptop powered for the requested run. It saves failure details and a resume command if a stage fails; it does not silently restart an unbounded job. A resume checks dataset/tokenizer/training signatures and uses the remaining recorded budget. It does not overwrite earlier model versions.

After training it automatically merges the selected checkpoint, exports F16/Q8/Q4 models, calibrates a Q4 variant using training data only, compares baseline/new predictions, checks capability retention, and runs the CPU benchmarks sequentially. No predicted file action executes. A completion entry is appended to root NOTES.md. Model promotion remains a review decision; inspect actual semantic failures, not just loss or exact string scores.

## Status, stop and resume (PowerShell, repository root)

```powershell
.\training\.venv\Scripts\python.exe training/status_five_hour.py
Get-Content training/runs/fily-edge-v5-5h/worker.stdout.log -Tail 12
```

To request a graceful stop, create the job's `CANCEL` marker:

```powershell
New-Item training/runs/fily-edge-v5-5h/CANCEL -ItemType File
```

A cancelled job is not a completed five-hour run. To resume an interrupted/failed job without a CANCEL marker:

```powershell
.\training\.venv\Scripts\python.exe training/run_five_hour.py --resume
```

The five-hour supervisor is started once as a hidden background process, with logs in its own output folder. All dataset/model/tooling files remain Git-ignored; keep an independent backup. No automatic chat notification is promised. The local status/report files are the source of truth.
