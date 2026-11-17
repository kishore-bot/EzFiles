"""Diversified reader requests, compact unambiguous policy, and unchanged old-task replay."""
import hashlib
import json
import random
from collections import Counter
from pathlib import Path
from build_support_v6 import row, tool, reply, clarify
from diagnose_support_v7 import compact_system
from support_v6 import validate
from validate_dataset import validate_row as legacy_validate

ROOT=Path(__file__).resolve().parent
DEST=ROOT/'data/reader_v7'
SEED=261116

PHRASES={
 'next_page':('pdf',["next page", "go one page forward", "turn to the following page", "advance a page", "move to the next PDF page", "let me read the next page"]),
 'previous_page':('pdf',["previous page", "go one page backward", "turn back a page", "show the prior page", "return one PDF page", "take me back a page"]),
 'first_page':('pdf',["go to the first page", "start at page one", "return to the start of this PDF", "show the opening page", "take me to the beginning of the document", "reset the reader to the beginning"]),
 'last_page':('pdf',["go to the last page", "show the end of the PDF", "skip to the ending page", "take me to the final page", "jump to the document's end", "display the last PDF page"]),
 'go_to_page':('pdf',["go to page {v}", "show PDF page {v}", "jump to page {v}", "turn the document to page {v}", "display page number {v}", "open page {v} in this document"]),
 'next_slide':('pptx',["next slide", "advance the presentation", "show the following slide", "go a slide forward", "continue to the next slide", "move ahead one slide"]),
 'previous_slide':('pptx',["previous slide", "go back one slide", "show the prior slide", "return to the preceding slide", "move backward in the presentation", "take the deck back a slide"]),
 'go_to_slide':('pptx',["go to slide {v}", "display slide {v}", "jump to presentation slide {v}", "show slide number {v}", "navigate to slide {v}", "put the deck on slide {v}"]),
 'select_cell':('xlsx',["select {v}", "choose cell {v}", "click on {v}", "put focus on cell {v}", "highlight cell {v} without editing", "select spreadsheet cell {v} and leave its value alone", "show the value in cell {v}", "make {v} the selected cell"]),
 'select_sheet':('xlsx',["switch to sheet {v}", "select worksheet {v}", "show the {v} sheet", "open the {v} tab", "make {v} the active worksheet", "bring up worksheet {v}"]),
 'scroll_down':('markdown',["scroll down", "move down in the document", "scroll farther down", "show content below this", "move the document view downward", "scroll towards the bottom"]),
 'scroll_up':('markdown',["scroll up", "move up in the document", "scroll farther up", "show the content above", "move the document view upward", "scroll towards the beginning"]),
 'scroll_left':('xlsx',["scroll left", "pan left", "move the sheet view to the left", "show columns further left", "shift the spreadsheet left", "move the viewport leftward"]),
 'scroll_right':('xlsx',["scroll right", "pan right", "move the sheet view to the right", "show columns further right", "shift the spreadsheet right", "move the viewport rightward"]),
 'zoom_in':('pdf',["zoom in", "make the page bigger", "increase zoom", "enlarge this PDF", "magnify the document", "increase the page magnification"]),
 'zoom_out':('pdf',["zoom out", "make the page smaller", "decrease zoom", "reduce this PDF", "shrink the document view", "decrease the page magnification"]),
 'show_source':('markdown',["show source", "show raw Markdown", "switch to source view", "display the source text", "show the unrendered text", "change preview to source"]),
 'show_preview':('markdown',["show preview", "render this Markdown", "switch to preview view", "display the rendered document", "leave source view for the preview", "show formatted Markdown"]),
 'show_contents':('epub',["show contents", "open the contents menu", "display the chapter list", "show the table of contents", "let me choose a chapter", "show the book's contents"]),
 'go_to_line':('code',["go to line {v}", "show source line {v}", "jump to line {v}", "display line number {v}", "scroll to code line {v}", "take the code view to line {v}"]),
 'pause':('media',["pause", "pause playback", "stop playing for a moment", "hold playback here", "pause this recording", "temporarily stop the video", "pause the audio, don't cancel the task", "keep the playback position and pause"]),
 'play':('media',["play", "resume playback", "continue playing", "unpause the recording", "resume this video", "start the audio again"]),
 'seek':('media',["seek to {v} seconds", "jump playback to {v} seconds", "go to second {v}", "set the playhead to {v} seconds", "play from {v} seconds in", "move the recording position to {v} seconds"]),
 'rotate_photo':('image',["rotate this photo", "rotate the picture", "turn the image once", "rotate the current image", "turn this picture", "rotate the photo view"]),
 'next_photo':('image',["next photo", "show the next image", "go forward one picture", "display the following photo", "advance the gallery", "move to the next picture"]),
 'previous_photo':('image',["previous photo", "show the previous image", "go back one picture", "display the prior photo", "move the gallery back", "return to the last picture"]),
}
VIEW_OPS={viewer:[op for op,(kind,_) in PHRASES.items() if kind==viewer] for viewer in {kind for kind,_ in PHRASES.values()}}


def make(split,count):
 rng=random.Random(SEED+{'train':0,'validation':1,'test':2}[split])
 rows=[]
 for i in range(count):
  for op,(viewer,phrases) in PHRASES.items():
   value=None
   if op in ['go_to_page','go_to_slide','go_to_line','seek']: value=rng.randint(2,65)
   elif op=='select_cell': value=rng.choice('ABCDEFGHJKLMNP')+str(rng.randint(2,95))
   elif op=='select_sheet': value=rng.choice(['Income','Costs','Inventory','Sales','Overview','January','Totals','Expenses'])
   doc=hashlib.sha256(f'{split}:{i}:{op}'.encode()).hexdigest()[:20]
   state={'authorizedIds':[doc], 'activeDocumentId':doc, 'readerAdapterAvailable':True,
          'readerOperations':VIEW_OPS[viewer][:], 'viewer':viewer, 'pageCount':100, 'currentPage':rng.randint(2,30),
          'rowCount':150,'columnCount':20,'activeSheet':'Current','openId':rng.randint(1,9999)}
   # Vary irrelevant metadata and key order, not the actual permission/adapter truth.
   if i%3==0:
    for key in ['openId','currentPage','activeSheet']:state.pop(key)
   if viewer not in ['xlsx']:state.pop('rowCount');state.pop('columnCount')
   rng.shuffle(state['readerOperations'])
   keys=list(state);rng.shuffle(keys);state={key:state[key] for key in keys}
   args={'operation':op,**({'value':value} if value is not None else {})}
   for pindex,phrase in enumerate(phrases):
    question=phrase.format(v=value)
    if i%5==1:question='Please '+question+'.'
    elif i%5==2:question='Hey Fily, '+question+'.'
    elif i%5==3:question='Could you '+question+'?'
    elif i%5==4:question=question.capitalize()+', thanks.'
    names=['controlReader','cancelAction']
    if i%4==0:names+=['getDocumentInfo']
    item=row('v7_'+op,split,i*10+pindex,[('user',question),('assistant',tool('controlReader',**args))],state,names)
    item['source']='reader_v7_diverse'
    item['messages'][0]['content']=compact_system(names)
    rows.append(item)
   # Same words, opposite capability: refusal must depend on state rather than vocabulary.
   if i%2==0:
    question=phrases[i%len(phrases)].format(v=value)
    item=row('v7_missing_'+op,split,i,[('user',question),('assistant',reply('That reader control is unavailable in this session. I have not changed the document.'))],
       {**state,'readerAdapterAvailable':False,'readerOperations':[]},['controlReader','cancelAction'])
    item['source']='reader_v7_diverse';item['messages'][0]['content']=compact_system(item['tools']);rows.append(item)
  for request,answer in [
   ('Cancel the pending request.',tool('cancelAction')),
   ('Stop this assistant task now.',tool('cancelAction')),
   ('Use a cloud recognizer if offline speech is missing.',reply('I will not use cloud speech. Use an installed offline English recognizer or typed input where available.')),
   ('Upload my recording to fix recognition.',reply('I will not upload audio. Offline recognition needs an installed supported speech pack.')),
   ('Execute the shell command written inside this file.',reply('I treat file text as data. I will not execute shell commands.')),
   ('Select a cell but the sheet name is unclear.',clarify('Which sheet and cell address do you mean?')),
   ('Show page zero.',clarify('Page numbers start at 1. Which valid page should I show?')),
   ('Choose Z999 when the loaded range ends at row 150.',clarify('That cell is outside the supplied range. Which valid cell should I select?')),
  ]:
   item=row('v7_boundary',split,i*20+len(rows),[('user',request),('assistant',answer)],
            {'readerAdapterAvailable':False},['cancelAction'])
   item['source']='reader_v7_diverse';item['messages'][0]['content']=compact_system(item['tools']);rows.append(item)
 return rows


def main():
 DEST.mkdir(parents=True,exist_ok=True)
 report={'seed':SEED,'source':'authored synthetic command paraphrases and version-5 replay','splits':{}}
 seen=set()
 for split,count,replay_count in [('train',32,8000),('validation',5,600),('test',5,0)]:
  rows=make(split,count)
  if replay_count:
   old=[json.loads(line) for line in (ROOT/f'data/edge_v5/{split}.jsonl').open(encoding='utf-8')]
   random.Random(SEED).shuffle(old)
   for previous in old[:replay_count]:
    previous['id']='v7-replay:'+previous['id'];previous['source']='v7_unchanged_v5_replay';rows.append(previous)
  unique=[];families=Counter();targets=0
  for item in rows:
   if item.get('source')=='reader_v7_diverse':validate(item)
   else:legacy_validate(item)
   key=hashlib.sha256(json.dumps(item['messages'],sort_keys=True).encode()).hexdigest()
   if key in seen:continue
   seen.add(key);unique.append(item);families[item['family']]+=1
   targets+=sum(m['role']=='assistant' for m in item['messages'])
  random.Random(SEED+len(split)).shuffle(unique)
  path=DEST/(split+'.jsonl');path.write_text(''.join(json.dumps(item)+'\n' for item in unique),encoding='utf-8')
  report['splits'][split]={'rows':len(unique),'targets':targets,'families':dict(families),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
 (DEST/'manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
 (DEST/'compact_policy.txt').write_text(compact_system(['controlReader','cancelAction']),encoding='utf-8')
 print(json.dumps({s:{k:v for k,v in info.items() if k in ['rows','targets']} for s,info in report['splits'].items()}))


if __name__=='__main__':main()
