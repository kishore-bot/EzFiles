"""Use versioned reader schemas with the existing bounded, non-executing GGUF evaluator."""
import evaluate_gguf
import validate_dataset
from support_v6 import TOOLS, validate


def validate_evaluation(row):
    if row["id"].startswith("support-v6:"):
        validate(row)
    else:
        # Old held-out suites retain their original contract; do not invent new required state.
        validate_dataset.validate_row(row)


evaluate_gguf.TOOLS = TOOLS
evaluate_gguf.validate_row = validate_evaluation

if __name__ == "__main__":
    evaluate_gguf.main()
