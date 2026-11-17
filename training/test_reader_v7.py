import json
import re
import unittest
from pathlib import Path
from build_reader_v7 import make
from diagnose_support_v7 import development, compact_system
from support_v6 import validate

ROOT=Path(__file__).resolve().parent


def normalized(text):return re.sub(r'[^a-z0-9]','',text.lower())


class ReaderImprovementTests(unittest.TestCase):
    def test_development_modes_validate(self):
        for mode in ['original','full','compact','compact_full','fewshot']:
            for item in development(mode):validate(item)

    def test_new_training_rows_validate(self):
        for item in make('train',2):validate(item)

    def test_reader_tools_are_not_described_as_unimplemented(self):
        prompt=compact_system(['controlReader','cancelAction'])
        self.assertNotIn('proposed_local_adapter',prompt)
        self.assertIn('Those operations are implemented',prompt)

    def test_identical_wording_is_conditioned_on_real_adapter_state(self):
        pairs={}
        for item in make('train',1):
            question=item['messages'][2]['content']
            answer=json.loads(item['messages'][-1]['content'])
            pairs.setdefault(question,[]).append((item['state'].get('readerAdapterAvailable'),answer['kind']))
        self.assertGreater(sum((True,'tool') in values and (False,'reply') in values for values in pairs.values()),20)

    def test_pause_is_not_assistant_cancellation(self):
        for item in make('train',1):
            if item['family']=='support_v7_pause':
                args=json.loads(item['messages'][-1]['content'])
                self.assertEqual(args['name'],'controlReader')
                self.assertEqual(args['arguments']['operation'],'pause')

    def test_sealed_and_original_acceptance_questions_absent_from_training(self):
        training=set()
        for line in (ROOT/'data/reader_v7/train.jsonl').read_text(encoding='utf-8').splitlines():
            for message in json.loads(line)['messages'][2:]:
                if message['role']=='user':training.add(normalized(message['content']))
        for source in ['reader_v7_holdout/sealed','support_v6/acceptance','diagnostic_v7/compact']:
            for line in (ROOT/'data'/f'{source}.jsonl').read_text(encoding='utf-8').splitlines():
                for message in json.loads(line)['messages'][2:]:
                    if message['role']=='user':self.assertNotIn(normalized(message['content']),training)


if __name__=='__main__':unittest.main()
