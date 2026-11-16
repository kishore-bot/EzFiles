# Fily model: what to train next

For whoever trains the next Fily model (LFM2-350M, the `training/` pipeline). Written from real phone sessions on 2026-11-16 (Nothing A059P, Q8_0 GGUF on the CPU) and the app code at commit `e2245db`.

## 0. Rules that do not change

- **The prompt contract is byte-exact.** The app builds the prompt in `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/FilyPrompt.kt`. The checked-in test `FilyPromptContractTest` compares it with `training/`. If you change POLICY, the tool JSON, APP_STATE fields or the turn layout, the app has to change in the same release. Write the change down in section 4 so the app side can be done together.
- **The current app speaks the v5 contract** (`training/exports/fily-edge-v5-5h`). `READER_V7_RUNTIME_CONTRACT.md` (activeDocumentId / readerOperations / compact prompt) is **not** what the app sends. Either train on v5's layout, or ship v7 together with the app change.
- **Output is always one of three JSON shapes**, enforced on the phone by a grammar: `{"kind":"tool",...}`, `{"kind":"reply","text":...,"citations":[...]}`, `{"kind":"clarify","text":...}`. Only the tools offered in `TOOLS=` can be named (the app offers at most 4 per request).
- **Speed budget.** On the phone each model call costs ~1-2 s (prompt read ~600 tok/s, answer ~25 tok/s). Most requests should finish in **one tool call plus one reply**. Every unnecessary clarify or extra tool step costs the user ~2 s.
- **Voice first.** Replies are spoken aloud: 1-2 short sentences, ideally under 30 tokens. No lists of more than 3 names.

## 1. What the app now does without the model

These are handled by plain matching before the model is called (`QuickCommands.kt`, `FilySession.kt`). The model does not need to learn them, but it must **not contradict them** when they appear in history, and should handle the variants the matcher misses:

| User says | App does |
|---|---|
| next / previous page, go to page N, first / last page, zoom in/out, scroll, scroll up/down/top/bottom | `controlReader` directly |
| select / click cell B3, next / previous sheet | `controlReader` directly |
| play, pause, skip to N seconds, next / previous photo, rotate | `controlReader` directly |
| open the first / second / last file, open number 3 | opens that file from the list on screen |
| close, exit, go back, go back to the library | closes the open file |
| go to the slides / PDF / images / media … section | switches the library filter |

History will contain these as normal exchanges (`controlReader` call → tool result → short reply, or a plain reply such as "Closed.").

## 2. Failures seen on the phone (train these first)

Each row is a real transcript from the phone log. "Want" is the target behaviour.

| # | User said (recognizer text) | Model did | Want |
|---|---|---|---|
| 1 | find my latest CV | searched correctly, then **clarify** "Would you like the most recent or the oldest match?" | "latest" was already answered by `sort:modified_desc`: reply naming the top file (or open it if the user said "open") |
| 2 | open random notes | `openDocument {"documentId":"random_note"}`: **made-up ID** | Only use IDs from a tool result or APP_STATE. With no ID yet: `searchLibrary {"query":"random notes"}` first |
| 3 | open resume | `openDocument {"documentId":"resume_1"}`, then reply "No resume is available **online**" | Search first. Never mention "online", the cloud, or things the tool did not say |
| 4 | open the first file UC on screen | `openDocument {"documentId":"UC"}` | Treat a recognizer slip ("UC") as noise. Use the listed-files state (section 4) |
| 5 | exit / I mean close this document | `getDocumentExcerpt`, then "Would you like the summary or further details?" / "Close the document." | Close (proposed tool in section 4). **Never claim an action that was not executed** |
| 6 | yes (after Fily's own question "open random_notes.txt?") | `searchLibrary {"category":"books"}` | Follow-ups: "yes" means do what you just offered; "no" means stop or ask what instead |
| 7 | scroll | `controlReader {"operation":"scroll"}` (not a valid operation) | Use only the enumerated operations: bare scroll → `scroll_down` |
| 8 | what does it say about the payment (open file is a résumé) | quoted an unrelated "Stripe payments" line as the answer | If the passages don't answer the question, say so: "This résumé doesn't mention payment terms." |
| 9 | what is my experience (résumé PDF open) | "Text access is limited." | Answer from the excerpt (years, roles, companies). Say "limited" only when the tool result has `available:false` |
| 10 | go to the slide section / I mean this life section of the app | `searchLibrary {"category":"slide"}` / `{"category":"life"}` | App navigation, not search (proposed `showSection`); "life" is a recognizer slip for "slide" |
| 11 | show my recent files | a 127-token reply, cut off at the length limit | "You have 10 recent files. The latest are A, B and C." |
| 12 | (search hit) | `"citations":[""]`, "I found an document match." | No empty citations; name the file; correct grammar |

## 3. Areas to train

### A. Never make up IDs (highest priority)
- IDs come only from tool results (`doc_1`, `doc_2` …), `APP_STATE.selectedDocumentId` or the proposed `listedFiles`.
- A name, a description or "it" with no ID in context → `searchLibrary` first.
- One match + "open" → `openDocument` with that ID. Several matches → a one-sentence clarify naming at most 3 ("PDF or Word résumé?").
- **Data:** ~1,500 multi-turn examples. Include every file type, misspelled and partial names, and names with digits or underscores.

### B. Knowing the current screen ("current app section")
The model should know where the user is. APP_STATE extension proposal (section 4): the library section, the tab, the visible files, and the open viewer's type and position.
- "What's open?", "Which page am I on?", "How many pages?", "What section is this?", "What files are here?" → answered from APP_STATE without a tool.
- "Open the third one", "the PDF one", "the one from yesterday" → resolve against `listedFiles`.
- Commands depend on the viewer: "next" means next page in a PDF, next photo in images, next track in media.
- **Data:** ~1,200 examples across every screen state.

### C. Video and audio (media player)
The app supports: `play`, `pause`, `seek` (seconds), `subtitles`, `audio_track`, plus full screen. Train:
- "Pause", "resume", "play it again", "skip 10 seconds", "go back 30 seconds", "jump to 2:30" (→ `seek` with 150), "go to the middle"/"the end" (needs duration from APP_STATE), "turn on subtitles", "change the audio language".
- "How long is this video?", "How much is left?" → answered from APP_STATE position and duration.
- "Play my latest video", "open the video I recorded yesterday" → `searchLibrary {"category":"video","sort":"modified_desc"}` then open.
- "Show my videos", "go to the media section" → section navigation.
- **Data:** ~800 examples. Relative seeks must turn into absolute seconds using the current position.

### D. Images (photo viewer)
- `next_photo`, `previous_photo`, `rotate_photo`, `zoom_in`/`zoom_out`, share, print, bookmark.
- "Show photos from last week", "open my latest screenshot", "find the photo of the receipt" (by name or date; the model cannot see pixels).
- "What does this image say?" / "read the text in this photo" → `openImageToText` (OCR tool). Don't guess the content.
- "Make a PDF of these photos" → `openImagesToPdf` / `createPdfFromImages`.
- **Data:** ~600 examples.

### E. Documents, intelligence and grounding
- **Grounded Q&A over excerpts:** answer only from `text`. Cite `[page N]` when the passage carries it ("On page 3, the fee is INR 48,000."). Say clearly when the answer isn't in the passages.
- **Summaries:** 2-3 spoken sentences. Never start with "The summary is:".
- **Extraction:** amounts, dates, names, emails, totals, due dates ("when is it due?", "who signed it?", "total amount?").
- **Résumé / CV questions:** experience, skills, education, contact details.
- **Code files** (.cpp, .java, .py …): "What does this program do?", "What language is it?", "Which functions are there?"
- **Spreadsheets/CSV:** "What's in column C?", "How many rows?", "Total of the salary column?" (from the excerpt; say when it's partial).
- **Date reasoning** with `APP_STATE.today`: "yesterday", "last week", "this month", "in October" → `after`/`before` on `searchLibrary`.
- **Size and count:** "biggest PDF" (`size_desc`), "how many PDFs do I have?" (count the results; say "at least N" when `partial:true`).
- **Compound requests:** "find my latest invoice and open it" → search, open, short reply, as one exchange of at most 2 tool calls.
- **Follow-ups and pronouns** within the last 2 exchanges: "open it", "the second one", "share that", "what about page 4", "and the other one?".
- **Data:** ~2,500 examples, with more hard negatives (questions the document can't answer).

### F. Robustness to speech recognition
The phone's offline recognizer makes mistakes. Real examples: "UC" for "you see", "receume", "back in engineer" for "backend engineer", "life section" for "slide section", "yas", "sumrise". Train with ASR-style noise: dropped and swapped words, homophones, fillers ("I mean", "no", "okay", "um"), self-corrections ("open the PDF, no, the Word one").
- **Data:** add noise to ~30% of all training requests.

### G. Clarify less
- Clarify only when there are two or more equally good matches **and** the request is an action (open, share, delete). For questions, answer from the top match and name it.
- Never ask "summary or details?": give the summary.

### H. Reply style for voice
- 1-2 sentences, under 30 tokens where possible. No markdown, lists or raw IDs in `text`.
- Name files without their extension unless it disambiguates ("your résumé PDF").
- Citations: only real IDs that were in the tool result. An empty array when there are none, never `""`.

### I. Optional, ask the owner first
- Code-mixed English (Hinglish/Tanglish) requests.
- Safety confirmations for destructive actions (the app already shows its own confirmation for share, print and PDF changes).

## 4. Proposed contract additions (need app work in the same release)

Ship each with the model that uses it. The app side is small, but must land together (see the "Fily model swap" rule: a new GGUF means swapping the app's tool catalog in the same change).

1. **`closeDocument`** `{}`: closes the open file (the app already has the action).
2. **`showSection`** `{"section": "all|recent|pdf|docs|slides|sheets|books|images|media|text|code|data|archives"}`: switches the library filter.
3. **`controlReader` operations**: the training catalog lists 16; the app supports 23. Add `scroll_down`, `scroll_up`, `scroll_top`, `scroll_bottom`, `select_cell` (value "B3"), `next_sheet`, `previous_sheet`.
4. **APP_STATE fields** (all optional, omitted when unknown):
   - `screen`: `"library" | "reader"`
   - `section`: the library filter, e.g. `"slides"`
   - `listedFiles`: the first 8 visible files, `[{"id":"doc_1","name":"…"}]`
   - `viewer`: `"pdf" | "image" | "video" | "audio" | "sheet" | "slides" | "text" | "code" | "book"`
   - `position`: `{"page":3}` / `{"seconds":95,"duration":600}` / `{"sheet":"Sales","cell":"B3"}`
5. **`searchLibrary.category` values**: fix the list and train only those (`resume, invoice, receipt, contract, bank_statement, id_document, book, slides, sheet, image, screenshot, video, audio, code, notes`). Today the model invents categories ("slide", "life", "books").

## 5. How the result is accepted

- **On the PC** (`evaluate_gguf.py`), against the v5 baseline (Q8: commands 127/128, tool calls 66/66, document questions 93/108):
  - no regressions on those sets;
  - a new held-out set per area A-H (≥100 each), each passing at ≥90%;
  - 0 made-up IDs in area A;
  - 0 "online"/cloud claims;
  - 0 invalid JSON.
- **On the phone** (`scripts/phone-bench.sh <gguf>`, voice-free replay through the real app):
  - median end-to-end time per request no worse than v5 (about 3.5-4.5 s on the CPU);
  - the 12 transcripts in section 2 replayed and correct.
- **Export:** Q8_0 GGUF; the existing filename pattern is `Fily-EzFiles-350M-Q8_0.gguf`. Q5/Q4 were measured slower and less accurate on this phone; don't ship them.
