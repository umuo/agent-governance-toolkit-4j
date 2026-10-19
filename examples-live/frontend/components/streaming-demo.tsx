'use client';

import { useEffect, useRef, useState } from 'react';
import { Code2, ShieldCheck, Square, Play } from 'lucide-react';
import { CollapsibleIntro } from '@/components/collapsible-intro';
import { SourceDialog } from '@/components/source-dialog';
import { consumeEvents } from '@/lib/stream';
import { applyDemoEvent, initialDemo, type StreamingCase } from '@/lib/streaming-demo';

const labels: Record<string, string> = { idle: '等待运行', running: '正在生成', done: '正常完成', blocked: '护栏已拦截', cancelled: '已停止', error: '运行失败' };
const descriptions: Record<string, string> = {
  REALTIME: '未经最终检查的实时预览；标记可能先显示，随后被最终检查拒绝。',
  GUARDED: '局部字面检查通过后放行安全前缀，未决尾部继续暂存。',
  STRICT: '全文生成并通过完整检查后交付；拒绝时不交付正文。',
};

export function StreamingDemo({ entry, onBusyChange }: { entry: StreamingCase; onBusyChange: (busy: boolean) => void }) {
  const [mode, setMode] = useState('GUARDED');
  const [provider, setProvider] = useState('fixture');
  const [message, setMessage] = useState(entry.prompt);
  const [state, setState] = useState(initialDemo);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [sourceOpen, setSourceOpen] = useState(false);
  const [source, setSource] = useState<{ code: string; configuration: string } | null>(null);
  const [sourceError, setSourceError] = useState('');
  const pending = useRef<AbortController | null>(null);
  const runId = useRef('');
  const stopRequested = useRef(false);
  const mounted = useRef(true);
  const output = useRef<HTMLPreElement>(null);

  useEffect(() => { onBusyChange(busy); }, [busy, onBusyChange]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; pending.current?.abort();
    if (runId.current && pending.current) void fetch(`/api/examples/streaming/runs/${runId.current}/cancel`, { method: 'POST', keepalive: true }).catch(() => {}); }; }, []);
  useEffect(() => { if (output.current) output.current.scrollTop = output.current.scrollHeight; }, [state.text]);
  useEffect(() => {
    if (!sourceOpen || source) return;
    const controller = new AbortController();
    setSourceError('');
    fetch(`/api/examples/streaming/${entry.id}/source`, { signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error('源码读取失败'); const value = await response.json(); if (!controller.signal.aborted) setSource(value); })
      .catch(error => { if (!controller.signal.aborted) setSourceError(error instanceof Error ? error.message : '源码读取失败'); });
    return () => controller.abort();
  }, [sourceOpen, source, entry.id]);

  async function cancelRun(id: string) {
    const response = await fetch(`/api/examples/streaming/runs/${id}/cancel`, { method: 'POST' });
    if (!response.ok) throw new Error('停止请求失败，请重试。');
  }
  async function stop() {
    stopRequested.current = true;
    if (!runId.current) return;
    try { await cancelRun(runId.current); }
    catch (error) { if (mounted.current) setError(error instanceof Error ? error.message : '停止失败'); }
  }
  async function run() {
    if (pending.current) return;
    const controller = new AbortController();
    pending.current = controller; runId.current = ''; stopRequested.current = false;
    setState({ ...initialDemo, status: 'running' }); setBusy(true); setError('');
    let terminal = false;
    try {
      const response = await fetch(`/api/examples/streaming/${entry.id}/run`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ mode, provider, message: provider === 'fixture' ? entry.prompt : message }),
      });
      if (!response.ok || !response.body) throw new Error(response.status === 429 ? '演示运行数已满，请稍后重试。' : `无法启动演示（${response.status}）`);
      await consumeEvents(response.body, event => {
        if (event.type === 'started') {
          runId.current = String(event.data.runId);
          if (stopRequested.current) void cancelRun(runId.current).catch(() => { if (mounted.current) setError('停止请求失败，请重试。'); });
        }
        if (event.type === 'result') terminal = true;
        if (mounted.current) setState(previous => applyDemoEvent(previous, event));
      });
      if (!terminal && !controller.signal.aborted) throw new Error('连接已结束，但没有收到运行结果。');
    } catch (error) {
      if (mounted.current && !controller.signal.aborted) {
        setError(error instanceof Error ? error.message : '连接失败');
        setState(previous => ({ ...previous, status: 'error' }));
      }
    } finally { pending.current = null; if (mounted.current) setBusy(false); }
  }
  const metrics = state.metrics;
  const noTrigger = state.status === 'done' && entry.id !== 'safe' && !metrics?.triggered;

  return <main className="main streaming-main">
    <CollapsibleIntro eyebrow="正式 SDK / 流式护栏" title={entry.name.slice(5)} badge={<span className="example-tag guarded"><ShieldCheck size={14} />ENFORCE</span>}>
      <section className="lesson-intro">
        <p>{entry.description}</p><p>{entry.expected}</p>
        <button onClick={() => setSourceOpen(true)} aria-haspopup="dialog"><Code2 size={15} />查看运行源码</button>
      </section>
    </CollapsibleIntro>
    <div className="conversation streaming-content">
      <section className="stream-controls" aria-label="运行配置">
        <label>输出来源<select value={provider} disabled={busy} onChange={event => setProvider(event.target.value)}><option value="fixture">固定分片 · 无需 API Key</option><option value="live">真实模型 · 使用后端 LLM 配置</option></select></label>
        <fieldset disabled={busy}><legend>交付模式</legend><div className="stream-modes">{['REALTIME', 'GUARDED', 'STRICT'].map(value => <label key={value}><input type="radio" name="delivery" value={value} checked={mode === value} onChange={() => setMode(value)} />{value}</label>)}</div></fieldset>
        <p className="stream-mode-description">{descriptions[mode]}</p>
        {provider === 'fixture' ? <details className="stream-fragments"><summary>固定模型分片（合成测试标记）</summary>{entry.fragments.map((fragment, index) => <code key={index}>{fragment}</code>)}<p>只模拟模型输出；检测、缓冲和中断全部由正式 SDK 执行。</p></details>
          : <label>模型提问<textarea value={message} disabled={busy} maxLength={16000} rows={3} onChange={event => setMessage(event.target.value)} /><small>真实模型的分片边界和是否输出标记均不保证；未命中时显示“未触发”。会使用后端配置的模型额度。</small></label>}
        <div className="lesson-actions"><button onClick={() => void run()} disabled={busy || (provider === 'live' && !message.trim())}><Play size={15} />运行案例</button><button onClick={() => void stop()} disabled={!busy}><Square size={14} />停止生成</button></div>
      </section>
      <section className="stream-result" aria-label="交付结果" aria-busy={busy}>
        <div className="message-heading"><strong role="status">{labels[state.status] || state.status}</strong><span>{state.runMode || mode} · {(state.runProvider || provider) === 'fixture' ? '固定分片' : '真实模型'}</span></div>
        {error && <p className="message-error" role="alert">{error}</p>}
        {state.code && <p className="stream-code">{state.code === 'model_unavailable' ? '模型不可用：请检查后端 LLM_API_KEY、LLM_MODEL 和 LLM_BASE_URL。' : state.code}</p>}
        {noTrigger && <p className="stream-notice" role="status">未触发：本次模型没有输出命中标记，不能据此认定拦截已验证。</p>}
        <pre ref={output} className="stream-output" aria-label="客户端实际收到的正文">{state.text || (busy ? '等待可交付的正文…' : '尚无正文交付')}</pre>
        <dl className="stream-metrics">
          <div><dt>首块时间</dt><dd>{metrics && metrics.firstChunkMs >= 0 ? `${metrics.firstChunkMs.toFixed(1)} ms` : '—'}</dd></div>
          <div><dt>模型接收字符</dt><dd>{metrics?.receivedCharacters ?? 0}</dd></div><div><dt>客户端交付字符</dt><dd>{metrics?.deliveredCharacters ?? 0}</dd></div>
          <div><dt>局部审计次数</dt><dd>{metrics?.localChecks ?? 0}</dd></div><div><dt>已观察取消次数</dt><dd>{metrics?.cancelAttempts ?? 0}</dd></div>
          <div><dt>总耗时</dt><dd>{metrics ? `${metrics.elapsedMs.toFixed(0)} ms` : '—'}</dd></div>
        </dl>
        <details><summary>检查记录（最近50条）</summary><ul className="stream-checks">{state.checks.map((check, index) => <li key={index}>{check.stage === 'local' ? '局部检查' : '完整检查'} · {check.verdict} · {check.rule}</li>)}</ul></details>
        <p className="stream-limit">仅保证所选字面规则的局部拦截；完整检查可能在已交付部分正文后拒绝。50 ms 是检测调度配置，并非端到端延迟承诺。</p>
      </section>
    </div>
    <SourceDialog open={sourceOpen} onClose={() => setSourceOpen(false)} title="流式护栏运行源码">
      {sourceError ? <p role="alert">{sourceError}</p> : !source ? <p role="status">正在读取源码…</p> : <><h3>固定案例与分片</h3><pre><code>{source.code}</code></pre><h3>正式 SDK 接入与模型配置</h3><pre><code>{source.configuration}</code></pre></>}
    </SourceDialog>
  </main>;
}
