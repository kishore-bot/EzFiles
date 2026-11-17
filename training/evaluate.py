"""Free-generation evaluation, without executing any predicted tool calls."""
import argparse
import json
import random
import copy
from pathlib import Path
import torch
from peft import PeftModel
from transformers import AutoTokenizer
from train import ROOT, load_model, load_rows
from validate_dataset import validate_row


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default=r"C:\Users\kisho\Downloads\LFM2")
    p.add_argument("--adapter")
    p.add_argument("--limit", type=int, default=30)
    p.add_argument("--output", default="evaluation.json")
    p.add_argument("--suite", choices=["test", "challenge", "acceptance", "domain_test"], default="test")
    args = p.parse_args()
    torch.set_num_threads(4)
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True)
    model, _ = load_model(args.model)
    if args.adapter:
        model = PeftModel.from_pretrained(model, ROOT / args.adapter)
    model.eval()
    candidates = []
    for row in load_rows(args.suite):
        for i, message in enumerate(row["messages"]):
            if message["role"] == "assistant":
                candidates.append((row, i))
    random.Random(1411).shuffle(candidates)
    results = []
    with torch.inference_mode():
        for row, i in candidates[:args.limit]:
            tokens = tokenizer.apply_chat_template(row["messages"][:i], tokenize=True, add_generation_prompt=True,
                                                   return_tensors="pt")
            if not isinstance(tokens, torch.Tensor):
                tokens = tokens["input_ids"]
            tokens = tokens.to("cuda")
            generated = model.generate(input_ids=tokens, max_new_tokens=180, do_sample=False,
                                       use_cache=True, pad_token_id=tokenizer.pad_token_id)
            raw = tokenizer.decode(generated[0, tokens.shape[-1]:], skip_special_tokens=True).strip()
            expected = json.loads(row["messages"][i]["content"])
            try:
                predicted = json.loads(raw)
            except json.JSONDecodeError:
                predicted = None
            contract_valid = False
            contract_error = None
            if predicted is not None:
                check = copy.deepcopy(row)
                check["messages"] = check["messages"][:i] + [{"role": "assistant", "content": raw}]
                try:
                    validate_row(check)
                    contract_valid = True
                except Exception as exc:
                    # Record failures; never execute generated actions.
                    contract_valid = False
                    contract_error = type(exc).__name__ + ": " + str(exc)
            results.append({"id": row["id"], "turn": i, "family": row["family"], "expected": expected,
                            "contract_valid": contract_valid,
                            "contract_error": contract_error,
                            "prediction": predicted, "raw": raw, "exact_match": predicted == expected})
            print(json.dumps({"completed": len(results), "exact_match": results[-1]["exact_match"], "family": row["family"]}), flush=True)
    tools = [r for r in results if r["expected"]["kind"] == "tool"]
    report = {"adapter": args.adapter, "suite": args.suite, "examples": len(results),
        "valid_json": sum(r["prediction"] is not None for r in results),
        "contract_valid": sum(r["contract_valid"] for r in results),
        "exact_matches": sum(r["exact_match"] for r in results),
        "tool_examples": len(tools), "tool_exact_matches": sum(r["exact_match"] for r in tools),
        "note": "Exact wording is strict for replies; inspect raw answers for grounded paraphrases. Teacher-forced history, not autonomous rollouts or speech.",
        "results": results}
    output = (ROOT / args.output).resolve()
    if not output.is_relative_to(ROOT):
        raise ValueError("Output must stay inside training/")
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}), flush=True)


if __name__ == "__main__":
    main()
