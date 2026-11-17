"""Teach short grounded app help from a verified, versioned local capability record."""
import json
import hashlib
from build_dataset import ROOT
from build_domain_dataset import FACTS
from catalog import system
from validate_dataset import validate_row


def main():
    facts={"domain_help_"+key:{"topic":key,"description":answer} for key,_,answer in FACTS}
    manifest=json.loads((ROOT/"data/domain_manifest.json").read_text())
    knowledge={"feature_document_sha256":manifest["features_sha256"],"topics":facts,"formats":manifest["formats"]}
    (ROOT/"data/app_capabilities.json").write_text(json.dumps(knowledge,indent=2)+"\n",encoding="utf-8")
    reports={}
    for split in ["domain_train","domain_validation","domain_test"]:
        rows=[json.loads(s) for s in (ROOT/"data"/f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
        for row in rows:
            if row["family"] in facts:
                row["state"]["capabilityFacts"]=facts[row["family"]]
            elif row["family"].startswith("domain_format_"):
                index=int(row["family"].rsplit("_",1)[1])
                row["state"]["capabilityFacts"]=manifest["formats"][index]
            row["messages"][0]["content"]=system(row["tools"])
            row["messages"][1]["content"]="APP_STATE="+json.dumps(row["state"],separators=(",",":"))
            row["id"]="grounded:"+row["id"]
            validate_row(row)
        dest=ROOT/"data"/f"grounded_{split}.jsonl"
        dest.write_text("".join(json.dumps(r)+"\n" for r in rows),encoding="utf-8")
        reports[split]={"rows":len(rows),"sha256":hashlib.sha256(dest.read_bytes()).hexdigest()}
    (ROOT/"data/grounded_domain_manifest.json").write_text(json.dumps(reports,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(reports))


if __name__=="__main__":
    main()
