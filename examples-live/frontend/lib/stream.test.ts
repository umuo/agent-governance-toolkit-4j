import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { consumeEvents, type AgentEvent } from './stream.ts';
describe('SSE protocol', () => {
  it('handles byte-level UTF-8 and CRLF fragmentation', async () => {
    const bytes = new TextEncoder().encode('event: text_delta\r\ndata: {"type":"text_delta","data":{"text":"你好"}}\r\n\r\n: heartbeat\n\ndata: {"type":"done","data":{}}\n\n');
    const stream = new ReadableStream<Uint8Array>({ start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close(); } });
    const events: AgentEvent[] = [];
    await consumeEvents(stream, event => events.push(event));
    assert.deepEqual(events.map(event => event.type), ['text_delta', 'done']);
    assert.equal(events[0].data.text, '你好');
  });
  it('rejects malformed events', async () => {
    const stream = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode('data: {}\n\n')); controller.close(); } });
    await assert.rejects(consumeEvents(stream, () => {}), /无效/);
  });
});
