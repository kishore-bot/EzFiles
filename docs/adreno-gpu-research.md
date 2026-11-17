# Fily on Adreno 810: research and proposed optimization

Researched 2026-11-16. This is a proposal, not a measured GPU improvement.

## What is verified locally

- Connected phone: A059P, Snapdragon SM7635, Qualcomm Adreno 810. Its OpenCL driver initializes successfully and registers GPUOpenCL.
- The inspected startup selected GPU=0 and offloaded 0/17 layers because App.kt explicitly requested CPU. Detection is working; those requests were not a GPU benchmark.
- Recent CPU generation measured roughly 24-26 tokens/second. Cold prompt processing took about 26 seconds in one exchange; later prompt processing was much shorter. These are application observations, not a controlled comparison.
- Build cache confirms Release, GGML_OPENCL=ON, embedded kernels, and Adreno kernels enabled for arm64-v8a. AndroidAppDependencies.kt already configures an app-private compiled-kernel cache. These options must be verified in use rather than counted as new optimizations.
- Native warm_up currently decodes only one BOS token. That does not establish that multi-token prefill kernels are compiled before the first question.
- ModelManager currently reserves 128 generated tokens and has a 30-second GPU inference timeout. The context is 2048 tokens. These limits also affect end-to-end reliability, independently of GPU throughput.
- In the interrupted implementation attempt, App.kt was changed to preferGpu=true. Nothing was installed. Its build encountered a missing speech-hints argument while unrelated speech files were being edited concurrently; those files were not changed by this work. That error must be rechecked against the settled tree before delivery.

## Recommended first path: tune the existing runtime and trained weights

1. Preserve the matching trained Q8 model as the accuracy baseline. Generate Q4_0, importance-matrix-calibrated Q4_K_M, and Q5_K_M candidates from the original merged F16/BF16 checkpoint, not by requantizing Q8. llama.cpp supports these quantization families on OpenCL, but does not promise equal speed or accuracy. Use representative app prompts for calibration and separate unseen commands for evaluation.
2. Retain higher precision for sensitive tensors only if the command tests show a need. The quantizer supports per-tensor overrides and separate embedding/output types. These are experiments, not an established optimal recipe for Fily. Tied embedding/output weights need inspection in the exported tensor map.
3. Confirm actual layer offload, OpenCL buffers and execution. GPU=1 is a request, not proof that all operations run on GPU. Inspect scheduler placement; the pinned OpenCL source has SSM_CONV support with F32 restrictions, so hybrid convolutions should not be assumed categorically unsupported or entirely accelerated.
4. Verify compiled-program cache HIT/MISS/SAVE traces with GGML_OPENCL_KERNEL_CACHE_DEBUG=1. The cache is already configured. A missing/unwritable cache silently returns to source compilation; distinguish first-run compilation from steady-state inference.
5. Experiment with representative multi-token prefill plus single-token decode warm-up before reporting readiness. Keep loading off the UI thread and bounded with a recovery path. Do not hide a driver hang by merely increasing the inference timeout. Warm-up can move setup cost earlier; it does not eliminate it.
6. Sweep microbatch sizes 64/128/256/512 and CPU orchestration threads 2/4, with context held constant at 2048 initially. Compare partial and full offload against explicit CPU-only mode. Smaller microbatches may improve responsiveness but can reduce throughput. Retain persistent contexts and valid prefix reuse; verify reuse with actual evaluated-token counts rather than the logged pre-reset context size.
7. Profile only if GPU remains slower. A diagnostic build can enable GGML_OPENCL_PROFILING, which adds overhead. Check time in quantized matrix-vector kernels, convolutions, CPU/GPU transfers and synchronization before changing kernels. Fused operations or GPU-side greedy sampling are later targets only where profiling shows material cost.

No new training is needed to test this path. If lower-bit quantization causes unacceptable command regressions, retain Q5/Q8 or consider quantization-aware fine-tuning as a separate experiment.

## Alternative found: adreno-llms

The author's open C++/OpenCL implementation explicitly supports LFM2.5-350M, including short convolutions, attention, quantized matrix-vector kernels, fused operations, and GPU argmax. This is relevant reference code for a model-specific engine.

Its published Adreno 620 results are 11.43 tokens/second FP16, 13.67 INT8, and 14.54 Q4, with Q4 TTFT about 0.79 seconds. These used a short prompt and 32-token greedy generation. They cannot predict Adreno 810 performance or be compared directly with Fily's hundreds-of-token tool prompts.

The model-specific README states INT8/Q4 prefill uses the M=1 path one token at a time. This is a significant concern for Fily's long policy/tool prompts. It also uses custom weight bundles rather than a drop-in GGUF replacement. A trial would need conversion of our trained checkpoint, tokenizer/prompt parity, action accuracy checks, long-prompt benchmarks, and a dependency/license review before integration. Do not replace our runtime based on its short-prompt headline.

Qualcomm's separately downloadable precompiled Adreno kernel library described by llama.cpp currently targets Snapdragon X2 GPUs and MoE matrix operations. It is not documented as an Adreno 810/LFM2 dense-model acceleration package.

## Acceptance and benchmark plan

- Use the identical checkpoint, prompt contract, library state and decode settings. Record runtime commit (currently local 56fd8a375), build flags, model hashes and driver version.
- First benchmark Q8 CPU versus Q8 OpenCL to isolate backend effects. Then compare each quant on both backends to isolate quantization effects.
- Record cold load, first request, warm requests and a fresh process with a populated kernel cache separately. Never include compilation in a steady-state tokens/second claim or exclude it from user-visible startup reporting.
- Measure prompt lengths 128/512/1024/1536 and output lengths 32/128, within context limits; use at least five warm repetitions. Measure actual Fily search -> tool -> reply and excerpt -> summary exchanges as well as llama-bench. The latter excludes tokenization and sampling overhead.
- Record wall-clock response time, first-token latency, prefill/decode throughput, RSS/PSS, temperature, UI responsiveness and CPU fallback. Run a sustained workload to detect throttling. Profile runs are not headline speed measurements.
- Re-run unseen commands and document questions: valid JSON, correct file IDs, arguments, clarification after ambiguous results, cancellation, grounded summaries and previous-task retention. A smaller file is not acceptable evidence of preserved quality.
- Promote a GPU/quant configuration only if it improves application latency while preserving agreed command accuracy and stability. Keep CPU fallback and optional GPU use. No speed multiplier is established yet.

## Primary sources

- llama.cpp OpenCL support, Adreno 810, supported quant types and program cache: https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md
- Quantization, importance matrices and tensor precision overrides: https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md
- Benchmark controls and exclusions: https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md
- LFM2.5 model architecture and deployment background: https://huggingface.co/LiquidAI/LFM2.5-350M
- Author's LFM2.5-350M Adreno port, performance conditions and serial quantized prefill: https://github.com/a8nova/adreno-llms/tree/main/src/models/lfm2-5-350m
- Author's optimization/profiling history, with historical experiments and caveats: https://github.com/a8nova/adreno-llms/blob/main/src/models/lfm2-5-350m/BENCHMARK.md
