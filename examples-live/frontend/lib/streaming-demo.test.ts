import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { applyDemoEvent, initialDemo } from './streaming-demo.ts';

describe('streaming demo events', () => {
  it('retains the delivered prefix on block without replaying a final response', () => {
    let state = applyDemoEvent(initialDemo, { type: 'started', data: { runId: 'demo' } });
    state = applyDemoEvent(state, { type: 'text_delta', data: { text: '安全前缀' } });
    state = applyDemoEvent(state, { type: 'result', data: { status: 'blocked', code: 'stream_content_denied' } });
    assert.equal(state.text, '安全前缀'); assert.equal(state.status, 'blocked'); assert.equal(state.runId, 'demo');
  });
  it('bounds check history and leaves no-trigger completion distinct from a block', () => {
    let state = initialDemo;
    for (let i = 0; i < 80; i++) state = applyDemoEvent(state, { type: 'check', data: { stage: 'local', verdict: 'ALLOW', rule: 'local' } });
    assert.equal(state.checks.length, 50);
    state = applyDemoEvent(state, { type: 'result', data: { status: 'done', code: '', metrics: { triggered: false } } });
    assert.equal(state.status, 'done'); assert.equal(state.metrics?.triggered, false);
  });
});
