# Fily local training workspace

This entire folder is ignored by Git. It contains a Windows Python 3.13 venv, synthetic English training data, training-only Python scripts and local adapter outputs. Application source is untouched. Keep a separate backup if you want to retain this work; Git will not preserve it.

## Start from a fresh clone

Git holds the scripts, docs, tool catalog, dependency lock and evaluation reports. Everything large is rebuilt locally (and stays git-ignored):

1. **Python env** (Windows, Python 3.13, NVIDIA GPU with CUDA 12.4):
   ```powershell
   py -3.13 -m venv training\.venv
   .\training\.venv\Scripts\python.exe -m pip install -r training\requirements-lock.txt
   ```
2. **Base model**: download [LiquidAI/LFM2.5-350M](https://huggingface.co/LiquidAI/LFM2.5-350M) into `C:\Users\<you>\Downloads\LFM2` (or pass `--model <folder>` to the scripts).
3. **Quantization tools** (llama.cpp binaries in `training/tools/`): `.\training\.venv\Scripts\python.exe training/get_quant_tools.py`.
4. **Data**: the builders are seeded, so they recreate the same files. For the current model: `build_edge_curriculum.py` (edge v5, replayed), then `build_app_v8.py`.
5. **Train, export, evaluate** in one detached job (see "Current state" below for the exact command).

The trained app model itself is published as a GitHub Release asset (`Fily-EzFiles-350M-Q8_0.gguf`), not in the repository.

## Current state (17 November 2026)

- **Shipped in the app:** `exports/fily-edge-v5-5h/Fily-EzFiles-350M-Q8_0.gguf` (adapter `runs/fily-edge-v5-5h/checkpoint-003102`). The app loads it from `/data/local/tmp` on the CPU. Measured on the phone (Nothing A059P, Snapdragon 7s Gen 3), Q8_0 on the CPU beat Q5/Q4 and the GPU; see `docs/adreno-gpu-research.md` and `scripts/phone-bench.sh` in the repository.
- **In training:** `runs/fily-app-v8` on `data/app_v8` (`build_app_v8.py`). It continues from the v5 checkpoint and fixes the phone failures listed in `docs/fily-training-needs.md`:
  - only real IDs (`doc_1`, `doc_2` … exactly as the app shows them);
  - answers grounded in excerpts, with `[page N]`;
  - "it doesn't say" when the passages don't answer;
  - follow-ups;
  - no needless clarifying;
  - recognizer slips.
  
  It replays 13,000 edge-v5 rows, with IDs rewritten to the app's style.
- **Contract:** the app's prompt (`FilyPrompt.kt`) is the v5 layout. Don't change POLICY, the tool JSON or APP_STATE fields without changing the app in the same release (`FilyPromptContractTest`). The v7 "reader" contract in `READER_V7_RUNTIME_CONTRACT.md` is **not** what the app sends.

Status of a run: `.\training\.venv\Scripts\python.exe training/status_five_hour.py` (it reads `runs/<run>/progress.json`). A run is a detached Windows process: closing VS Code does not stop it. After a reboot, resume with `run_five_hour.py --resume` plus the same `--output/--data/--init-adapter`.

Older history: [QUANTIZATION_REPORT.md](QUANTIZATION_REPORT.md), [FIVE_HOUR_RUN.md](FIVE_HOUR_RUN.md), [EVALUATION.md](EVALUATION.md).

## Model and environment

Base checkpoint: `C:\Users\kisho\Downloads\LFM2`, identified by its local README/config as LiquidAI/LFM2.5-350M. `model.safetensors` is the pretrained weight file. Files in Downloads are read only. Model license remains the owner's responsibility before distribution; see the original LICENSE. Training does not make a new unrestricted license.

The venv uses the supplied PyTorch 2.6.0+cu124 wheel, Transformers, PEFT, Accelerate and jsonschema. Exact installed versions are recorded in requirements-lock.txt. Network was used to install dependencies, not for documents, training or model inference. Training scripts require CUDA and local weights; there is no CPU or network fallback.

## Dataset contract

`build_dataset.py` creates train/validation/test JSONL and a manifest with hashes, counts and provenance. The curriculum combines everyday English, hesitation/ASR-like text, requests for all main feature groups, temporal sorting, category/semantic search, exclusions, folder scope, exact dictation, references/corrections, grounded summaries/QA/comparison, partial results, permission revocation, unavailable files, unsupported requests, prompt injection, cancellation and honest workflow status. All documents and IDs are fictional; no personal document contents were read.

Outputs are JSON envelopes: `tool` with a name/arguments, `reply` with a short spoken text/citations, or `clarify` with a question. User-facing prose is inside the envelope; these files are not an app integration. No predicted action is ever executed during training/evaluation.

`tool_catalog.json` defines 33 existing-workflow representations and four explicitly proposed local adapters. Parameter schemas are a **training contract**, not a claim of exact Kotlin/AppFunctions ABI compatibility. `searchLibrary` represents richer future local retrieval; `controlReader` represents future voice controls; `proposeFilePlan` only proposes changes; `cancelAction` requires an adapter. Existing action names were grounded in the repository feature catalog and assistant sources. Integration must reconcile every schema and enforce permission, capability and confirmation rules in application code.

The corpus is an authored synthetic starting curriculum, not thousands of independently written human conversations. Splits hold out generated entities and request wrappers but share task families and response patterns; some fixed boundary utterances recur with different tool contexts. Exact duplicate conversations are removed across splits, which does not eliminate semantic/template overlap. A good synthetic score is not proof of generalization to real speech. Capture consented English user requests and create an independent human-reviewed benchmark before shipping.

## Commands (repository root, PowerShell)

```powershell
.\training\.venv\Scripts\python.exe training/build_dataset.py
.\training\.venv\Scripts\python.exe training/validate_dataset.py
.\training\.venv\Scripts\python.exe training/build_challenges.py
.\training\.venv\Scripts\python.exe -m unittest discover -s training -p test_training.py -v
.\training\.venv\Scripts\python.exe training/evaluate.py --limit 30 --output baseline-evaluation.json
.\training\.venv\Scripts\python.exe training/train.py --smoke --steps 2 --accumulation 1 --output runs/gpu-smoke-v1
.\training\.venv\Scripts\python.exe training/train.py --output runs/fily-lora-v1
.\training\.venv\Scripts\python.exe training/evaluate.py --adapter runs/fily-lora-v1 --limit 30 --output trained-evaluation.json
.\training\.venv\Scripts\python.exe training/evaluate.py --adapter runs/fily-lora-v1 --suite challenge --limit 100 --output challenge-evaluation.json
```

Use a fresh output directory for a new run; scripts refuse to overwrite a saved adapter. Default training performs one epoch, batch size one, four gradient-accumulation microbatches, rank-8 LoRA, checkpointing and supported GPU mixed precision. Only assistant target tokens contribute loss. Overlong conversations fail preflight rather than losing context/answers through silent truncation. The GPU smoke test stresses the longest samples. Native PyTorch convolution fallback is correct but slower than optional platform-specific kernels; no custom CUDA package is required here.

Outputs include adapter weights/config/tokenizer, progress, per-step metrics, peak allocated GPU memory, base-model hash and validation losses. Checkpoints every 100 steps include optimizer/RNG snapshots; automatic resume is not implemented. Base model weights are not modified. The final adapter is not a standalone GGUF or Android package: loading requires the original model. Merging/export, phone quantization, wake detection, speech recognition, speech output and app integration are separate work.

Evaluation uses free generation on fixed held-out test turns with supplied correct history. It records strict JSON/action/argument exact match; reply wording exact match is deliberately strict and needs manual review of grounded paraphrases. It is not an autonomous multistep rollout, jailbreak certification, or audio evaluation. Review every missed category and test permissions in app code before enabling execution.

`build_challenges.py` provides 24 separately authored held-out conversations: incomplete dates, misleading filenames, instruction injection, revisions, negations, conditional requests, three-way ordering, permission changes, absent media tracks and workflow-status traps. These are excluded from training and require manual review as well as exact-match scoring. Read `data/validation_report.json`, `runs/*/report.json` and the evaluation reports for actual results; the existence of a saved adapter alone is not evidence of a reliable assistant.

## Second pass and honest evaluation scope

The first adapter matched all 30 sampled synthetic test turns, but the authored challenge exposed semantic and formatting failures. `build_hard_dataset.py` adds new contrasting examples for dates versus filenames, partial counts, delivery status, cancellation, absent tracks, exact dictation, negation, evidence/citations and summaries. It does not read or copy challenge records. The challenge failure categories informed this pass, so that set is now a development diagnostic rather than an untouched final benchmark. Old training examples are replayed to reduce forgetting; the original data and v1 adapter are retained.

```powershell
.\training\.venv\Scripts\python.exe training/build_hard_dataset.py
.\training\.venv\Scripts\python.exe training/train.py --init-adapter runs/fily-lora-v1 --train-file data/hard_train.jsonl --validation-file data/hard_validation.jsonl --learning-rate 0.00005 --output runs/fily-lora-v2
.\training\.venv\Scripts\python.exe training/build_acceptance.py
.\training\.venv\Scripts\python.exe training/evaluate.py --adapter runs/fily-lora-v2 --suite acceptance --limit 100 --output acceptance-evaluation-v2.json
```

`build_acceptance.py` supplies 16 fresh acceptance conversations authored after second-pass data was frozen. These also remain synthetic and use supplied correct tool histories. Strict response-string matches do not recognize equivalent paraphrases; review the actual answers. Passing a JSON schema does not establish factual accuracy or safe execution. No evaluated tool call is executed.

## Read before implementation

- `.gitignore`
- `NOTES.md` (latest relevant checkpoints)
- `docs/FEATURES.md` (feature/action and capability ranges)
- `Downloads/LFM2/config.json`
- `Downloads/LFM2/tokenizer_config.json`
- `Downloads/LFM2/chat_template.jinja` (formatting ranges)
- `Downloads/LFM2/README.md` (identity and training ranges)
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantActions.kt` (bounded action ranges)
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantContext.kt`
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantDraft.kt` (signature matches)
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantTidyActions.kt` (signature matches)
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantNavigation.kt`
- `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/AssistantAccess.kt` (policy ranges)

Primary references: [LFM2 Transformers](https://huggingface.co/docs/transformers/model_doc/lfm2), [PEFT LoRA](https://huggingface.co/docs/peft/en/quicktour), [model card](https://huggingface.co/LiquidAI/LFM2.5-350M).
