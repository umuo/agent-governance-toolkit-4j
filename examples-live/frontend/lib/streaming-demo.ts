import type { AgentEvent } from './stream.ts';

export type StreamingCase = { id: string; name: string; description: string; prompt: string; expected: string; marker: string; fragments: string[] };
export type Metrics = { receivedCharacters: number; deliveredCharacters: number; localChecks: number; firstChunkMs: number; elapsedMs: number; cancelAttempts: number; triggered: boolean };
export type DemoState = { runId: string; runMode: string; runProvider: string; text: string; status: string; code: string; metrics?: Metrics; checks: { stage: string; verdict: string; rule: string }[] };
export const initialDemo: DemoState = { runId: '', runMode: '', runProvider: '', text: '', status: 'idle', code: '', checks: [] };
export function applyDemoEvent(state: DemoState, event: AgentEvent): DemoState {
  const data = event.data;
  const metrics = data.metrics as Metrics | undefined;
  switch (event.type) {
    case 'started': return { ...state, runId: String(data.runId), runMode: String(data.mode ?? ''), runProvider: String(data.provider ?? ''), status: 'running' };
    case 'text_delta': return { ...state, text: state.text + String(data.text), metrics: metrics ?? state.metrics };
    case 'check': return { ...state, metrics: metrics ?? state.metrics, checks: [...state.checks.slice(-49), { stage: String(data.stage), verdict: String(data.verdict), rule: String(data.rule) }] };
    case 'result': return { ...state, status: String(data.status), code: String(data.code), metrics: metrics ?? state.metrics };
    default: return state;
  }
}
