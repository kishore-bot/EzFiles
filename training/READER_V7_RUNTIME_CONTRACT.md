# Reader v7 candidate: runtime contract

The candidate is a model plus a compact prompt protocol. It is not an app implementation or an automatic replacement. Keep the existing model until the recorded regression results justify a change. App source and installed models remain untouched by the training tools.

## Prompt and current state

Use the selected candidate's `runtime_reader_prompt.txt` as a reader-specific prompt example. Build tools from the operations the app actually implements for the current viewer. The training helper `diagnose_support_v7.compact_system` removes obsolete `proposed_local_adapter` descriptions from advertised tools. This does not authorize unavailable operations: do not advertise an adapter until it works.

Send APP_STATE in its own user message before the request. A working reader session supplies:

```json
{"enabled":true,"contentAllowed":false,"authorizedIds":["opaque-id"],"activeDocumentId":"opaque-id","readerAdapterAvailable":true,"readerOperations":["next_page","previous_page","go_to_page"],"viewer":"pdf","currentPage":5,"pageCount":20,"openId":42}
```

Use actual state values. `readerAdapterAvailable: false` or a missing operation must stop execution. `selectedDocumentId`/`readerCapabilities` from an older app prompt are different field names; normalize actual app state explicitly rather than silently inventing an available adapter. For spreadsheets supply the active sheet and validated dimensions. Page/slide numbers and cell addresses in model arguments are user-facing, one-based values.

The model proposes `{"kind":"tool","name":"controlReader","arguments":{"operation":"next_page"}}`. The application validates the schema, current document/session, operation support, permissions and bounds before executing. It returns a real completed/queued/failed/cancelled result. Selecting a cell must not edit a value; pausing media must not cancel the assistant session.

Only expose supported actions. A model request cannot create missing next-page/cell/move adapters. Missing permissions, stale references and changed documents require an honest failure or clarification. Stop/disable must interrupt real pending work, independently of what the model says. Offline-only behavior must also be enforced by the app; a generated sentence is not a security control.

## Evaluation boundaries

- Original v6 acceptance is a regression set after its failures were inspected. Its exact questions are excluded from v7 training, but it is no longer pristine independent evidence.
- New development probes are used for prompt/checkpoint selection. They are not reported as independent accuracy.
- The 40-case/32-command sealed benchmark was frozen before candidate selection and is not used for training or checkpoint selection. It remains authored synthetic text, not human speech or real device acceptance.
- Report model-and-prompt results separately from unchanged-prompt results. Keep exact tool names and arguments as the strict primary command metric; do not improve numbers by treating arbitrary replies as successful actions.
- Use separate legacy validation examples to check forgetting during selection; run the original 128-turn/66-tool test only after selection. No automatic promotion is performed.

## Local commands

The bounded trial uses `runs/fily-reader-v7-trial`, at most 900 training-loop seconds and 400 optimizer steps. It starts from the previous v5 best adapter with 8,000 v5 training replay conversations. A separate finishing worker waits for training, selects among checkpoints using development only, exports GGUFs and checks the frozen regression sets.

```powershell
Get-Content training/runs/fily-reader-v7-trial/progress.json
Get-Content training/runs/fily-reader-v7-trial/finish.json
Get-Content training/reader-v7-finish.log -Tail 8
```

Final results, when present, are `training/exports/fily-reader-v7-candidate/REVIEW.md`. Completion does not imply every metric improved; inspect failures before deciding whether to use the candidate. Training/evaluation files remain ignored by Git. Previous v5/v6 models are preserved.
