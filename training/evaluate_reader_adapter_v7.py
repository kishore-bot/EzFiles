"""Development-only CUDA generation for checkpoint selection; no acceptance/phone actions."""
import argparse
import json
import os
import time
from pathlib import Path
import torch
from peft import PeftModel
from transformers import AutoTokenizer
from train import ROOT, load_model
from evaluate_support_v6 import validate_evaluation


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--adapters',nargs='+',required=True)
    parser.add_argument('--output',required=True)
    parser.add_argument('--suite',default='diagnostic_v7/compact')
    args=parser.parse_args()
    assert args.suite.startswith('diagnostic_v7/'), 'Checkpoint selection is development-only'
    rows=[json.loads(line) for line in (ROOT/'data'/f'{args.suite}.jsonl').open(encoding='utf-8')]
    tokenizer=AutoTokenizer.from_pretrained(r'C:\Users\kisho\Downloads\LFM2',local_files_only=True)
    base,dtype=load_model(r'C:\Users\kisho\Downloads\LFM2')
    model=None
    reports=[]
    for number,adapter in enumerate(args.adapters):
        path=(ROOT/adapter).resolve();assert path.is_relative_to(ROOT)
        name='candidate_'+str(number)
        if model is None:model=PeftModel.from_pretrained(base,path,adapter_name=name)
        else:model.load_adapter(path,adapter_name=name)
        model.set_adapter(name);model.eval();model.config.use_cache=True
        results=[]
        for item in rows:
            prompt=tokenizer.apply_chat_template(item['messages'][:-1],tokenize=False,add_generation_prompt=True)
            inputs=tokenizer(prompt,return_tensors='pt',add_special_tokens=False).to('cuda')
            started=time.perf_counter()
            with torch.inference_mode():
                output=model.generate(**inputs,do_sample=False,max_new_tokens=96,use_cache=True,
                                     pad_token_id=tokenizer.eos_token_id)
            raw=tokenizer.decode(output[0,inputs['input_ids'].shape[1]:],skip_special_tokens=True).strip()
            expected=json.loads(item['messages'][-1]['content'])
            try:
                prediction=json.loads(raw)
                check={**item,'messages':item['messages'][:-1]+[{'role':'assistant','content':raw}]}
                validate_evaluation(check);valid=True
            except Exception: prediction=None;valid=False
            results.append({'id':item['id'],'expected':expected,'prediction':prediction,'raw':raw,
                            'exact_match':prediction==expected,'contract_valid':valid,'seconds':time.perf_counter()-started})
            if len(results)%8==0:
                print(json.dumps({'adapter':adapter,'development_completed':len(results),'total':len(rows)}),flush=True)
        groups={}
        for result in results:
            group='reader' if result['id'].startswith('support-v6:') else 'legacy'
            record=groups.setdefault(group,{'examples':0,'exact':0})
            record['examples']+=1;record['exact']+=result['exact_match']
        report={'adapter':adapter,'suite':args.suite,'examples':len(results),'groups':groups,
                'exact_tools':sum(r['exact_match'] for r in results),'valid':sum(r['contract_valid'] for r in results),'results':results}
        reports.append(report)
        dest=(ROOT/args.output).resolve();assert dest.is_relative_to(ROOT)
        dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(json.dumps(reports,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k!='results'}),flush=True)


if __name__=='__main__':main()
