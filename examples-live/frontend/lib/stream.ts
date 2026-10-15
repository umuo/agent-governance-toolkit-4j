export type AgentEvent = { type: string; data: Record<string, unknown> };
/** Handles UTF-8 fragmentation, CRLF boundaries and multiple SSE data lines. */
export async function consumeEvents(body: ReadableStream<Uint8Array>, onEvent: (event: AgentEvent) => void) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  function dispatch(block: string) {
    const data = block.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
    if (!data) return;
    const parsed = JSON.parse(data) as AgentEvent;
    if (typeof parsed.type !== 'string' || typeof parsed.data !== 'object' || parsed.data === null) throw new Error('无效的 Agent 事件');
    onEvent(parsed);
  }
  function drain() {
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      dispatch(buffer.slice(0, match.index));
      buffer = buffer.slice(match.index + match[0].length);
    }
    if (buffer.length > 2_000_000) throw new Error('事件超过大小限制');
  }
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      drain();
    }
    buffer += decoder.decode();
    drain();
    if (buffer.trim()) dispatch(buffer);
  } finally { reader.releaseLock(); }
}
