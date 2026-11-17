import copy
import json
import unittest
from build_support_v6 import acceptance, cases, row, tool, reply
from support_v6 import validate


class SupportCurriculumTests(unittest.TestCase):
    def test_all_small_variants_follow_schema_and_capabilities(self):
        for split in ["train", "validation", "test"]:
            examples = list(cases(split, 2))
            self.assertGreater(len(examples), 350)
            for item in examples: validate(item)

    def test_heldout_cases_validate(self):
        for item in acceptance(): validate(item)

    def test_reader_action_without_adapter_is_rejected(self):
        item = row("bad", "unit", 0, [("user", "next page"), ("assistant", tool("controlReader", operation="next_page"))],
                   {"authorizedIds": ["d1"], "activeDocumentId": "d1", "readerOperations": ["next_page"]}, ["controlReader"])
        with self.assertRaises(AssertionError): validate(item)

    def test_unsupported_operation_is_rejected(self):
        item = row("bad", "unit", 0, [("user", "next page"), ("assistant", tool("controlReader", operation="next_page"))],
                   {"authorizedIds": ["d1"], "activeDocumentId": "d1", "readerAdapterAvailable": True, "readerOperations": []}, ["controlReader"])
        with self.assertRaises(AssertionError): validate(item)

    def test_cross_split_conversations_are_distinct(self):
        groups = [{json.dumps(x["messages"], sort_keys=True) for x in cases(split, 2)} for split in ["train", "validation", "test"]]
        for i in range(3):
            for j in range(i): self.assertFalse(groups[i] & groups[j])

    def test_document_role_markers_are_escaped(self):
        item = next(x for x in cases("test", 1) if x["family"] == "support_untrusted_code")
        self.assertNotIn("<|im_start|>", next(m["content"] for m in item["messages"] if m["role"] == "tool"))

    def test_failed_reader_requests_do_not_claim_success(self):
        for item in cases("train", 5):
            if item["family"].startswith("support_reader_result_"):
                result = json.loads(next(m["content"] for m in item["messages"] if m["role"] == "tool"))
                self.assertNotEqual(result["status"], "completed")
                answer = json.loads(item["messages"][-1]["content"])["text"]
                self.assertNotIn("The viewer completed", answer)

    def test_legacy_retention_keeps_its_original_contract(self):
        from evaluate_support_v6 import validate_evaluation
        from pathlib import Path
        source = Path(__file__).resolve().parent / "data/edge_v5/test.jsonl"
        previous = next(json.loads(line) for line in source.open(encoding="utf-8") if 'reader_show_source' in line)
        validate_evaluation(previous)


if __name__ == "__main__": unittest.main()
