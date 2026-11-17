"""Finish a bounded targeted trial, select on development only, then evaluate frozen tests."""
import argparse
import copy
import hashlib
import json
import os
import random
import shutil
import time
from datetime import datetime,timezone
from pathlib import Path
from run_five_hour import ROOT, run, write, job_lock
from diagnose_support_v7 import compact_system, development

JOB=ROOT/'runs/fily-reader-v7-trial'
EXPORT=ROOT/'exports/fily-reader-v7-candidate'


def rel(path):return str(path.relative_to(ROOT))


def transform(source,dest):
    rows=[json.loads(line) for line in source.open(encoding='utf-8')]
    for item in rows:item['messages'][0]['content']=compact_system(item['tools'])
    dest.write_text(''.join(json.dumps(item)+'\n' for item in rows),encoding='utf-8')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--wait',action='store_true');args=parser.parse_args()
    JOB.mkdir(parents=True,exist_ok=True)
    with job_lock(JOB/'finish.lock'):
      state={'status':'waiting_for_training','pid':os.getpid()};write(JOB/'finish.json',state)
      started=time.monotonic()
      while not (JOB/'report.json').exists():
        if (JOB/'failure.json').exists():raise RuntimeError((JOB/'failure.json').read_text())
        if not args.wait or time.monotonic()-started>1800:raise RuntimeError('Training has not finished; run with --wait after checking the worker.')
        time.sleep(5)
      report=json.loads((JOB/'report.json').read_text())
      assert report['status']=='completed'
      # Selection never accesses the original acceptance or newly sealed answers.
      state['status']='development_selection';write(JOB/'finish.json',state)
      dev=ROOT/'data/diagnostic_v7';dev.mkdir(parents=True,exist_ok=True)
      old=[json.loads(line) for line in (ROOT/'data/edge_v5/validation.jsonl').open(encoding='utf-8')]
      random.Random(11819).shuffle(old)
      legacy=[];families=set()
      for item in old:
        if item['family'] in families:continue
        last=item['messages'][-1]
        if last['role']!='assistant' or len(last['content'])>210:continue
        if json.loads(last['content']).get('kind')!='tool':continue
        families.add(item['family']);legacy.append(item)
        if len(legacy)==32:break
      assert len(legacy)==32
      selection=development('compact')+legacy
      (dev/'selection.jsonl').write_text(''.join(json.dumps(item)+'\n' for item in selection),encoding='utf-8')
      checkpoints=sorted(JOB.glob('checkpoint-*'))
      candidates=[checkpoints[0],checkpoints[len(checkpoints)//2],JOB/Path(report['selected_adapter']).name]
      candidates=list(dict.fromkeys(candidates))
      run('evaluate_reader_adapter_v7.py','--adapters',*[rel(path) for path in candidates],
          '--suite','diagnostic_v7/selection','--output',rel(JOB/'selection.json'))
      evaluations=json.loads((JOB/'selection.json').read_text())
      baseline=evaluations[0]['groups']['legacy']['exact']
      eligible=[item for item in evaluations if item['groups']['legacy']['exact']>=baseline]
      selected=max(eligible,key=lambda item:(item['groups']['reader']['exact'],item['groups']['legacy']['exact']))
      write(JOB/'selected.json',{'selection_source':'development only; new sealed/original acceptance not used',
            'adapter':selected['adapter'],'groups':selected['groups'],'all_candidates':[{k:v for k,v in item.items() if k!='results'} for item in evaluations]})
      state.update(status='exporting',selected_adapter=selected['adapter']);write(JOB/'finish.json',state)
      if not (EXPORT/'manifest.json').exists():run('export_quantized.py','--adapter',selected['adapter'],'--output',rel(EXPORT))
      assert json.loads((EXPORT/'manifest.json').read_text())['adapter']==selected['adapter']
      shutil.copy2(ROOT/'data/reader_v7/manifest.json',EXPORT/'training_dataset_manifest.json')
      (EXPORT/'runtime_reader_prompt.txt').write_text(compact_system(['controlReader','cancelAction'])+'\n',encoding='utf-8')
      # Freeze the candidate before looking at either held-out benchmark.
      manifest=json.loads((ROOT/'data/reader_v7_holdout/manifest.json').read_text())
      sealed=ROOT/'data/reader_v7_holdout/sealed.jsonl'
      assert hashlib.sha256(sealed.read_bytes()).hexdigest()==manifest['sha256']
      dest=ROOT/'data/reader_v7_evaluation';dest.mkdir(parents=True,exist_ok=True)
      transform(sealed,dest/'fresh_compact.jsonl')
      transform(ROOT/'data/support_v6/acceptance.jsonl',dest/'old_acceptance_compact.jsonl')
      model=rel(EXPORT/'Fily-EzFiles-350M-Q8_0.gguf')
      baseline_model='exports/fily-edge-v5-5h/Fily-EzFiles-350M-Q8_0.gguf'
      tests=[('old_acceptance_original',model,'support_v6/acceptance',30,True),
             ('old_acceptance_compact',model,'reader_v7_evaluation/old_acceptance_compact',30,True),
             ('baseline_fresh_compact',baseline_model,'reader_v7_evaluation/fresh_compact',40,True),
             ('candidate_fresh_compact',model,'reader_v7_evaluation/fresh_compact',40,True),
             ('retention_original',model,'edge_v5/test',128,False)]
      state['status']='heldout_evaluation';write(JOB/'finish.json',state)
      final_tests=[]
      for label,weights,suite,limit,free in tests:
        path=EXPORT/('evaluation-'+label+'.json')
        if not path.exists():run('evaluate_support_v6.py','--model',weights,'--suite',suite,'--limit',limit,'--output',rel(path),*(['--unconstrained'] if free else []))
        value=json.loads(path.read_text())
        final_tests.append({'label':label,**{key:val for key,val in value.items() if key!='results'}})
      final={**state,'status':'completed','completed_utc':datetime.now(timezone.utc).isoformat(),
             'development':selected['groups'],'tests':final_tests,'installed_or_promoted':False,
             'limits':'Small authored text tests. Prompt protocol changed; original acceptance is now regression, not pristine independent evidence. No app action or phone test.'}
      write(JOB/'finish.json',final)
      lines=['# Reader v7 candidate review','',f"Selected on development: {selected['adapter']}",
             'The compact prompt is part of this candidate. Compare original and compact results separately.','',
             '| Test | Exact tools | Valid contract |','|---|---:|---:|']
      for item in final_tests:lines.append(f"| {item['label']} | {item['tool_exact_matches']}/{item['tool_examples']} | {item['contract_valid']}/{item['examples']} |")
      lines+=['',final['limits'],'','No automatic promotion or installation. Review errors and original-task retention.']
      (EXPORT/'REVIEW.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
      print(json.dumps({key:value for key,value in final.items() if key!='tests'}),flush=True)


if __name__=='__main__':main()
