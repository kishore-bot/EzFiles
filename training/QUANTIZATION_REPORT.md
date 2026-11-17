# Fily EzFiles domain model and quantization

Completed 14 November 2026. Scope: English file requests, EzFiles feature/format help, brief conversation, clarification and structured local action requests. App source was not edited.

**Quality default: Q8_0. Smaller/faster option: calibrated Q4_K_M-I.** These are development exports, with synthetic conditional evaluation rather than phone acceptance.

## Size, quality and local CPU speed

| Export | MB | Prompt tokens/sec | Generated tokens/sec | Exact action checks | Exact format lists | Strict reply/action matches |
|---|---:|---:|---:|---:|---:|---:|
| F16 | 711.49 | 369.8 | 59.8 | 7/7 | 14/14 | 43/45 |
| Q8_0 | 379.22 | 436.7 | 96.8 | 7/7 | 14/14 | 43/45 |
| Q4_K_M | 229.31 | 967.5 | 159.8 | not evaluated | not evaluated | not evaluated |
| Q4_K_M-I | 229.31 | 1015.9 | 159.5 | 7/7 | 13/14 | 39/45 |
| Q4_0 | 219.31 | 805.1 | 173.3 | 3/7 | 13/14 | 19/45 |

Calibrated Q4 is 3.10 times smaller than F16 and 2.67 times faster at token generation in this host test. Q8 is 1.62 times faster than F16 and retains the stronger tested feature accuracy.

The smallest Q4_0 export loses action/response quality and is not recommended. Calibrated Q4 preserved all seven action checks but refused a ZIP-extension question despite supplied facts. Q8 and F16 answered all fourteen extension lists; their two strict string mismatches were capitalization and equivalent clarification wording. Strict response matching does not grade all semantic paraphrases.

## Benchmark method

Pinned official llama.cpp b12377 Windows CPU binaries; four threads; no GPU layers; three repeats; 256 prompt tokens and 64 generated tokens; native warmup. The final run followed all training/evaluation tasks. Earlier speed results that overlapped a smoke test are preliminary and excluded here. These are **Windows host CPU numbers**, not Snapdragon/Android measurements or end-to-end voice latency. Raw results include CPU/build details and per-test variation in exports/fily-domain-v4/bench-*.json.

## Training and grounded knowledge

V3 narrowed the existing adapter to the requested domain: 744 conversations/824 assistant targets, including 160 older replay conversations. It completed 206 RTX 2050 optimizer steps in 303.92 seconds. A grounded V4 continuation supplied a verified 8,542-byte capability record rather than relying on invented feature recall; another 206 steps completed in 333.49 seconds. V4 sampled validation loss fell from 0.081215 to 0.006025, with no truncation. These are synthetic curriculum results, not evidence that arbitrary English is understood.

The capability record has 14 documented format groups and 28 topics, tied to the docs/FEATURES.md hash. A caller must select relevant facts and supply them as APP_STATE.capabilityFacts, plus current authorized file IDs, selection and actual tool results. Model weights do not contain the user's files. Search/extraction supplies current document facts. The record must be updated if app capabilities change.

Merged trained weights and tokenizer are embedded in each standalone GGUF; neither original HF base weights nor a separate LoRA is needed at inference. An inference engine is still required. The capability JSON and tool schemas are small runtime context, not extra language models. Original model license is included.

## Evaluation boundary and corrections

All four evaluated precisions used the same 45 assistant turns from 42 authored synthetic conversations, with supplied correct histories, a small permitted tool subset and relevant capability facts. Seven action turns exercise search/open/excerpt selection, not every app workflow. All 14 format groups are included. This set was selected after domain training and shares its task families/templates; it is not an independent human benchmark. JSON envelopes/tool names were constrained; complete arguments, IDs and citations were validated after decoding. No predicted tool executed.

The original strict per-tool JSON grammar reordered optional/required argument properties and dropped resume category arguments. That harness error was fixed; preliminary reports are labelled pre-argument-order-fix and excluded. Token-ID prompts avoid an additional BOS token; a regression test covers this. Output constraints prevent some syntax errors, not incorrect semantics.

Importance-aware Q4 calibration used 32 training records, 16 chunks of 512 tokens, and 92 measured matrix entries. Held-out evaluation text was not used for calibration. Hashes/provenance are in calibration-manifest.json.

## Reproduce

```powershell
.\training\.venv\Scripts\python.exe training/build_domain_dataset.py
.\training\.venv\Scripts\python.exe training/build_grounded_domain.py
.\training\.venv\Scripts\python.exe training/export_quantized.py --adapter runs/fily-domain-v4 --output exports/NEW-EXPORT
.\training\.venv\Scripts\python.exe training/calibrate_quantization.py --export exports/NEW-EXPORT
.\training\.venv\Scripts\python.exe training/evaluate_gguf.py --model exports/fily-domain-v4/Fily-EzFiles-350M-Q8_0.gguf --suite quant_compare --limit 100 --output exports/fily-domain-v4/new-evaluation-Q8.json
.\training\.venv\Scripts\python.exe training/bench_quantized.py --export exports/fily-domain-v4
.\training\.venv\Scripts\python.exe -m unittest discover -s training -p test_training.py -q
```

Training commands/parameters are also in runs/fily-domain-v3/report.json and runs/fily-domain-v4/report.json. The full training folder, tools, datasets and weights remain Git-ignored.

## Remaining work

The app must supply authorized scope, retrieval, a validated action broker and actual success/failure results. Four action contracts are explicitly future adapters; existing workflow schemas also require ABI reconciliation. The model does not independently read files or execute app functions. Optional wake detection, local ASR, TTS, cancellation, model unloading, real-user language tests and phone performance remain app/device work. No app integration or phone acceptance is claimed.
