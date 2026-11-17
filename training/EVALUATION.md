# Fily training evaluation - 14 November 2026

Status: **development prototype; not accepted for unattended file control**. No app integration or tool execution occurred.

## Generation results

| Model / suite | Turns | JSON valid | Contract valid | Exact tool outputs | Overall strict matches |
|---|---:|---:|---:|---:|---:|
| Base model, sampled test | 30 | 8 | not recorded | 1/21 | 1/30 |
| V1, sampled test | 30 | 30 | 30 | 21/21 | 30/30 |
| V1, challenge | 33 | 32 | 29 | 12/16 | 12/33 |
| V2, sampled test | 30 | 30 | 30 | 21/21 | 30/30 |
| V2, development challenge | 33 | 31 | 29 | 15/16 | 15/33 |
| V2, fresh acceptance | 20 | 20 | 19 | 8/11 | 8/20 |

Exact tool outputs require the action name and all arguments to match. Overall strict matches also demand identical reply wording, so correct paraphrases can fail that metric. Contract validity checks format, known tools, authorization IDs and citation references; it does not establish factual correctness. Tests supply correct prior conversation/tool history; no autonomous rollout or audio test was performed.

The original synthetic splits share templates/task families and some fixed utterances. The development challenge informed the second pass by failure category; it was not copied into training, but its second evaluation is not an untouched benchmark. The 16 fresh acceptance conversations (20 assistant turns) were excluded from both passes. All examples are synthetic.

## What still fails

Manual review found real errors, not just strict wording differences:

- Casual latest-versus-last-opened requests can lose their date sort and become relevance searches.
- Exact dictation can be paraphrased or repunctuated.
- Some requests for a limited excerpt/draft include extra facts the user did not request.
- New summaries can omit the main decision or describe a decreased price as an increase.
- Instruction-like document text can contaminate an answer with invented facts, even without an actual tool call.
- Some outputs have malformed JSON, extra fields, ambiguous clarification or unavailable-track suggestions.
- The model can propose an unsupported rewrite instead of explaining the boundary.

Neither adapter should execute file actions without an external validator and explicit application review. The trained model does not supply wake detection, speech recognition, speech synthesis, document extraction, a vector index, an MCP server or Android adapters.

## Training evidence

- v1: 798 CUDA optimizer steps, 1329.91 seconds, validation loss 0.929235 to 0.042181, peak allocated tensor memory 1566.8 MiB.
- v2: 335 CUDA optimizer steps, 614.05 seconds, validation loss 1.289887 to 0.005774, peak allocated tensor memory 1562.8 MiB.

Losses are from fixed 24-example samples within each pass; the two passes use different validation sets. Peak tensor allocation is not total GPU memory. Original local model weights remain unchanged; saved adapters contain 184 finite tensors each.

## Dataset and reproducibility

- First pass: 2,494 training conversations, 104 families, 37 action contracts; 311 validation and 311 test conversations.
- Second pass: 540 new contrastive conversations plus 384 original replay conversations. Total unique training conversations across passes: 3,034.
- Separate diagnostic: 24 challenge conversations. Fresh acceptance: 16 conversations.
- Ten training regression checks passed; all generated rows passed schema/ID/access/evidence checks. No training or validation example was truncated.
- See README.md, requirements-lock.txt, data/manifest.json, data/hard_manifest.json, runs/*/report.json and the raw *evaluation*.json reports.

Next work: add independently collected/consented English requests and reviewed grounded summaries, broaden date/coreference/dictation contrasts, evaluate summary faithfulness and adversarial content with a fresh benchmark, and compare a larger model if 350M remains brittle. Quantized phone export and app integration remain separate work.
