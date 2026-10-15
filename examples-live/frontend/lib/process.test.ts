import { it } from 'node:test';
import assert from 'node:assert/strict';
import { appendModelDelta, appendReference, modelContent, type Activity } from './process.ts';
it('retains reasoning and content across tool calls and model rounds in arrival order', () => {
  let entries: Activity[] = [];
  entries = appendModelDelta(entries, 'reasoning', 1, '先检查');
  entries = appendModelDelta(entries, 'reasoning', 1, '文件');
  entries = appendModelDelta(entries, 'content', 1, '我会读取文件。');
  entries = appendReference(entries, 'tool', 'call-1');
  entries = appendReference(entries, 'approval', 'approval-1');
  entries = appendModelDelta(entries, 'reasoning', 2, '根据工具结果');
  entries = appendModelDelta(entries, 'content', 2, '# 完成');
  assert.deepEqual(entries.map(item => item.kind), ['reasoning', 'content', 'tool', 'approval', 'reasoning', 'content']);
  assert.equal('text' in entries[0] && entries[0].text, '先检查文件');
  assert.equal(modelContent(entries, 1), '我会读取文件。');
  assert.equal(modelContent(entries, 2), '# 完成');
  assert.equal(appendReference(entries, 'tool', 'call-1').length, entries.length);
});
it('does not join non-consecutive content or invent missing reasoning', () => {
  let entries: Activity[] = appendModelDelta([], 'content', 1, 'before');
  entries = appendReference(entries, 'tool', 'tool');
  entries = appendModelDelta(entries, 'content', 1, 'after');
  assert.deepEqual(entries.map(item => item.kind), ['content', 'tool', 'content']);
  assert.equal(modelContent(entries, 1), 'beforeafter');
  assert.equal(entries.some(item => item.kind === 'reasoning'), false);
});
