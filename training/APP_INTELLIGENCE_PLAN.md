# Fily: useful intelligence inside EzFiles

Scope: English, local files and app features, optional on/off. The language model interprets requests, asks questions and explains verified results. File access, retrieval, rendering and execution remain application services. These are product proposals, not implemented app features.

| Experience | Example | Model contribution | App work / boundary |
|---|---|---|---|
| Conversational discovery | "The CV I edited last, then the Word one" | Intent, filters, pronouns, corrections | Authorized hybrid search, stable IDs, current dates and candidate state |
| Version comparison | "What changed between these contracts?" | Explain retrieved differences with citations | Extract both versions, compare reliably; do not infer versions from filenames alone |
| Document questions | "What is the deadline and amount?" | Grounded answers, uncertainty and short summaries | Bounded extraction, page/offset evidence, partial coverage flags |
| Related documents | "Find the receipt for this invoice" | Entity/relationship suggestions | Local searchable entities/edges with evidence; confidence and correction UI |
| Filing plans | "Group my work invoices by month" | Suggest labels, grouping and destinations | Dry-run plan, permission recheck, user review; no silent move/delete |
| Duplicate explanations | "Why are these called duplicates?" | Explain exact equality versus similarity | Fresh byte checks and existing review flow; model guesses never authorize deletion |
| Guided PDF work | "Combine these, cover page first" | Ordered tool planning and clarifications | Existing merge/extract/export review, original preservation and output selection |
| Capture assistant | "Scan this bill, then help name it" | Guide workflow and suggest a grounded title | Camera/OCR permissions, capture review, extraction quality and Save As |
| Reading companion | "Read that paragraph; show the next page" | Resolve context, summarize or request reader action | Reader capability adapter, local TTS, accessible controls and stop/cancel |
| Document edits | "Replace this company name in the deck" | Understand replacement intent and scope | Use actual supported Save As editing APIs; existing training catalog does not expose every edit |
| Deadline awareness | "Which documents mention something due soon?" | Extract and explain dates; flag ambiguous dates | Local index/date resolution. Notifications/calendar need a separate explicit adapter |
| Local preferences | "Use my usual PDF layout" | Apply supplied preferences naturally | Opt-in local storage, inspect/delete controls; no implied persistent memory |
| Proactive suggestions | "These scans may need OCR" | Explain an available next step | Explicit opt-in, battery/idle scheduling, authorized scope and dismissible suggestions |
| Recovery help | "Why couldn't you open it?" | Explain actual failure and next step | Real provider/password/codec/grant errors; no invented success or cloud fallback |
| App help | "Can you read this format?" | Friendly answer from capability facts | Versioned capability record; do not hallucinate unsupported extensions/functions |

Prioritize conversational discovery, faithful document answers, app help and guided existing workflows first. Add relationship indexing, filing plans and preference memory when the supporting services/UI exist. Keep inferred entities/dates/version links inspectable and correctable. A large framework, graph database or multiple agents is not required for these initial experiences.

## Training emphasis

The expanded curriculum covers ordinary requests as well as missing dates, copied timestamps, stale results, same-name files, unknown ownership, process/context loss, disabled access, missing local bytes, passwords, bad extraction, partial summaries, conflicting revisions, document instructions, invalid page ranges, ordered selections, save/share/print failures, unavailable media tracks, exact dictation, preference opt-out and interrupted multi-step actions.

Training can teach proposed workflows to explain limitations or prepare plans. It cannot create missing app APIs, guarantee factuality, hear a wake word, or replace permission/execution validation. Quantized models must be evaluated separately. More GPU hours should be judged by held-out behavior, not elapsed time alone.
