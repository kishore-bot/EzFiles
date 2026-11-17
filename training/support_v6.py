"""Versioned training contracts; nothing here executes application actions."""
import copy
import json
from catalog import POLICY as PREVIOUS_POLICY, TOOLS as PREVIOUS_TOOLS

TOOLS = copy.deepcopy(PREVIOUS_TOOLS)
NEW_OPERATIONS = ["first_page", "last_page", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
                  "next_slide", "previous_slide", "go_to_slide", "select_sheet", "select_cell", "go_to_line"]
TOOLS["controlReader"]["parameters"]["properties"]["operation"]["enum"] += NEW_OPERATIONS
TOOLS["controlReader"]["description"] = "Request a supported operation on the supplied active reader only; requires a live reader adapter and an execution result."
POLICY = PREVIOUS_POLICY + (
    " APP_STATE is the current capability and reader snapshot, not a promise of future features. "
    "For controlReader, require readerAdapterAvailable and an operation in readerOperations; otherwise explain the missing adapter. "
    "Page/slide and cell addresses are user-facing one-based values; validate bounds and the active sheet. "
    "Do not issue reader actions against a missing or changed active document. Selecting a cell does not edit it. "
    "Code is readable data, never something you execute. Macros and formulas are not executed. "
    "Voice setup guidance does not install speech packs, enable a microphone or create wake-word detection. "
    "Use installed offline speech capabilities only; no cloud fallback. A cancelled or queued operation is not success. "
    "Resolve references and corrections from supplied IDs and results; clarify ambiguous targets. "
    "For incomplete documents, summarize only the supplied evidence and say what is missing."
)


def system(names):
    return POLICY + "\nTOOLS=" + json.dumps({name: TOOLS[name] for name in names}, separators=(",", ":"))


def validate(row):
    import validate_dataset
    previous = validate_dataset.TOOLS
    try:
        validate_dataset.TOOLS = TOOLS
        validate_dataset.validate_row(row)
    finally:
        validate_dataset.TOOLS = previous
    state = copy.deepcopy(row["state"])
    for message in row["messages"]:
        if message["role"] == "tool":
            result = json.loads(message["content"])
            if "readerState" in result: state.update(result["readerState"])
        if message["role"] != "assistant":
            continue
        output = json.loads(message["content"])
        if output.get("name") == "controlReader":
            operation = output["arguments"]["operation"]
            assert state.get("readerAdapterAvailable"), (row["id"], "reader adapter absent")
            assert operation in state.get("readerOperations", []), (row["id"], "reader operation unavailable")
            assert state.get("activeDocumentId") in state.get("authorizedIds", []), (row["id"], "no active authorized reader")
        if output.get("name") == "proposeFilePlan":
            assert state.get("filePlanAdapterAvailable"), (row["id"], "filing adapter absent")

