# Fily v6: depth-focused app support and voice-command curriculum

The owner replaced the earlier eight-hour preference with a four-hour training-loop budget. The objective is useful behavior, not the biggest dataset or the most elapsed time. Preparation, final export and evaluation take additional time. No app source, native GPU backend, installed phone model or application dependency is changed by this job.

The reference is `APP_SUPPORT_V6.md`. It combines short support cards, a hashed snapshot of `docs/FEATURES.md`, and versioned reader contracts. Current supplied runtime capabilities take precedence. Some documented features and actual adapters differ; unverified archive behavior is intentionally excluded from the short support cards.

## What changes

- Grounded help for PDF, modern Word and PowerPoint, spreadsheets, EPUB, Markdown, code/config, supported images/media, OCR, creation/editing, sharing/printing, permissions, Tidy and recovery.
- Exact support lists come from the current feature table, rather than an older model's memory.
- Reader operations cover page/slide navigation, scroll, source/preview, code lines, sheet/cell selection, zoom, photos and playback. These are **proposed training contracts**, not installed app functions. The model must withhold actions when the current reader adapter or operation is unavailable.
- Multi-step search/open/navigation waits for real completion; queued, failed and cancelled requests cannot be described as completed. Corrections and changed active documents must not act on the wrong target.
- Source examples are explained without executing code. Markdown/code instructions cannot override user intent. Drafting preserves supplied text; cancelled Save As does not become a saved output.
- Voice setup explains microphone grants, English ASR/TTS, unavailable offline packs, missing wake-word detection and CPU/GPU limits. This is language-model training on text, not training a speech recognizer, TTS or wake-word model.
- Conflicting revisions, incomplete text, missing modification dates and unverified spreadsheet formulas must produce qualified answers or clarification.

All examples are synthetic and use fictional identifiers/text. Existing authorized v5 training data supplies 6,000 replay conversations; separate v5 validation data supplies 400 replay conversations. No v5 test row enters training or checkpoint selection. Normal splits share authored templates; exact deduplication does not establish semantic independence. Thirty separately authored acceptance conversations are reserved for after training, including unconstrained tool output tests.

## Quality and resource controls

Continue the previous best v5 adapter (`checkpoint-003102`), not the final worse checkpoint. Use local RTX 2050 CUDA, rank-8 adapter continuation, assistant-only masking, batch one, accumulation four, learning rate 2e-5, max sequence 1,536 tokens and gradient checkpointing. The worker refuses overlong examples and has no CPU fallback. Every 15 minutes it evaluates/checkpoints; the best fixed validation loss is retained, including the initial checkpoint if no training checkpoint improves it.

The four-hour loop is bounded to 14,400 seconds. Cache preparation/model loading/initial validation and final export/evaluation are outside that loop. Model files stay in a new directory. Before/after Q8 and calibrated Q4 tests include ordinary held-out support, unconstrained support/acceptance, and old v5 retention. Results are not automatically promoted or installed. Inspect failures and document faithfulness before selecting a new model. Local CPU benchmarks are not phone timings.

## PowerShell commands, from the repository root

Monitor the already running job (do not launch a duplicate):

```powershell
.\training\.venv\Scripts\python.exe training/status_support_v6.py
Get-Content training/runs/fily-support-v6-4h/worker.stdout.log -Tail 12
nvidia-smi
```

Request a graceful cancellation:

```powershell
New-Item training/runs/fily-support-v6-4h/CANCEL -ItemType File
```

A cancelled job is not a completed four-hour run and does not automatically export. A failed/interrupted job with a recoverable checkpoint can resume its recorded remaining budget (do not use this while the worker is alive):

```powershell
.\training\.venv\Scripts\python.exe training/run_support_v6.py --resume
```

The supervisor runs hidden, records stage/errors, uses a Windows file lock to reject a duplicate worker, and requests that Windows stay awake while it runs. It does not survive power-off or override an intentional sleep/shutdown. Keep the laptop plugged in with ventilation. There is no promised chat notification; `pipeline.json`, `progress.json`, `RUN_REPORT.md` and `completed.json` are the source of truth.

Expected final exports are under `training/exports/fily-support-v6-4h/`. Existing v5 exports are preserved. `completed.json` signifies that training and post-processing finished; it does not signify production quality or complete app/device validation.
