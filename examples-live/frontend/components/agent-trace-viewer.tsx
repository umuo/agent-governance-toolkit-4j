'use client';

import { useEffect, useState } from 'react';
import { GitBranch, Play, RefreshCw } from 'lucide-react';
import { traceStartError } from '@/lib/trace-errors';
import { SourceDialog } from '@/components/source-dialog';
import { CollapsibleIntro } from '@/components/collapsible-intro';

type Payload = { kind: string; body: unknown; truncated: boolean; originalBytes: number };
const payloadLabels: Record<string, string> = { agent_request: 'Agent 任务输入', agent_response: 'Agent 输出结果', model_request: '框架模型请求', model_response: '框架模型响应', http_request: '实际 HTTP 请求体', http_response: '实际 HTTP 响应体', model_error: '模型调用错误摘要', http_error: 'HTTP 调用错误摘要', tool_request: '工具请求参数', tool_effective_arguments: '校验后的实际执行参数', tool_response: '工具执行结果', agent_authority: '委托权限与父会话', task_budget: '根任务共享预算' };
const payloadText = (payload: Payload) => typeof payload.body === 'string' ? payload.body : JSON.stringify(payload.body, null, 2);
type Node = { payloads?: Payload[]; spanId: string; parentSpanId: string; name: string; kind: string; agentId: string | null; runId: string; parentRunId: string | null; agentInstanceId: string; startNanos: number; endNanos: number; status: string; reasonCode: string | null };
type Audit = { sequence: number; agentId: string; runId: string; spanId: string; sessionId: string; actionId: string; point: string; verdict: string; reasonCode: string | null; outcome: string | null };
type Trace = { traceId: string; createdAt: number; status: string; spans: Node[]; audit: Audit[]; effects: Record<string, number> };
const statusLabel: Record<string, string> = { running: '运行中', completed: '已完成', blocked: '已拒绝', error: '运行失败', interrupted: '进程中断' };
const kindLabel: Record<string, string> = { agent: 'Agent', delegate: '派生任务', model: '模型请求', tool: '工具动作' };

function SpanTree({ nodes, parent, selected, onSelect, depth = 0 }: { nodes: Node[]; parent: string | null; selected: string; onSelect: (id: string) => void; depth?: number }) {
  const ids = new Set(nodes.map(node => node.spanId));
  const children = nodes.filter(node => parent ? node.parentSpanId === parent : !ids.has(node.parentSpanId));
  if (!children.length || depth > 30) return null;
  return <ul className="span-tree">{children.map(node => <li key={node.spanId}>
    <button type="button" className={`span-node ${node.status}`} aria-pressed={selected === node.spanId} onClick={() => onSelect(node.spanId)}>
      <span className="span-kind">{kindLabel[node.kind] || node.kind}</span><strong>{node.name}</strong><span>{statusLabel[node.status] || node.status}</span>
      {node.reasonCode && <code>{node.reasonCode}</code>}
    </button>
    <SpanTree nodes={nodes} parent={node.spanId} selected={selected} onSelect={onSelect} depth={depth + 1} />
  </li>)}</ul>;
}

export function AgentTraceViewer() {
  const [history, setHistory] = useState<Pick<Trace, 'traceId' | 'createdAt' | 'status'>[]>([]);
  const [trace, setTrace] = useState<Trace | null>(null);
  const [traceId, setTraceId] = useState('');
  const [selected, setSelected] = useState('');
  const [inspected, setInspected] = useState<Payload | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    // 短 GET 请求不在 effect 清理时主动中断，避免检查器把正常清理报成 Fetch 失败。
    // 仍须在读取响应体后检查作用域，防止旧响应覆盖新选择。
    let active = true;
    fetch('/api/agent-traces').then(async response => {
      if (!response.ok) throw new Error('无法读取链路历史，请确认后端已更新。');
      const rows: Pick<Trace, 'traceId' | 'createdAt' | 'status'>[] = await response.json();
      if (!active) return;
      setHistory(rows);
      setTraceId(previous => previous || rows[0]?.traceId || '');
    }).catch(error => { if (active) setError(error instanceof Error ? error.message : '无法读取链路历史'); });
    return () => { active = false; };
  }, [reload]);

  useEffect(() => {
    if (!traceId) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let active = true;
    async function poll() {
      try {
        const response = await fetch(`/api/agent-traces/${traceId}`);
        if (!response.ok) throw new Error('读取链路失败，请重新选择或刷新历史。');
        const value: Trace = await response.json();
        if (!active) return;
        setTrace(value); setSelected(previous => value.spans.some(node => node.spanId === previous) ? previous : value.spans[0]?.spanId || '');
        if (value.status === 'running') timer = setTimeout(poll, 1000);
        else setHistory(previous => [value, ...previous.filter(item => item.traceId !== value.traceId)]);
      } catch (error) { if (active) setError(error instanceof Error ? error.message : '请求失败'); }
    }
    void poll();
    return () => { active = false; clearTimeout(timer); };
  }, [traceId, reload]);

  async function start() {
    setBusy(true); setError('');
    try {
      const response = await fetch('/api/agent-traces', { method: 'POST' });
      if (!response.ok) {
        const failure: { code?: string } = await response.json().catch(() => ({}));
        throw new Error(traceStartError(response.status, failure?.code));
      }
      const value: { traceId: string } = await response.json();
      setTrace(null); setSelected(''); setTraceId(value.traceId);
    } catch (error) { setError(error instanceof Error ? error.message : '启动失败'); }
    finally { setBusy(false); }
  }
  const node = trace?.spans.find(value => value.spanId === selected);
  const payloads = node?.payloads || [];
  const audits = trace?.audit.filter(row => row.spanId === selected) || [];
  const first = trace?.spans[0]?.startNanos || 0;
  const end = Math.max(first + 1, ...trace?.spans.map(node => node.endNanos || Date.now() * 1e6) || []);
  const running = busy || trace?.status === 'running';

  return <main className="main simple-main trace-main">
    <CollapsibleIntro eyebrow="可观测性 / 多 Agent" title="父子 Agent 执行链路" badge={<span className="example-tag guarded"><GitBranch size={14} />OpenTelemetry</span>}>
      <section className="lesson-intro"><p>跟随一次任务，看父 Agent 如何派生两个并行子 Agent，再定位到工具执行和护栏拒绝。</p>
        <div className="lesson-actions"><button type="button" onClick={() => void start()} disabled={running}><Play size={14} />{running ? '任务运行中…' : '运行多 Agent 演示'}</button><button type="button" onClick={() => { setError(''); setReload(value => value + 1); }}><RefreshCw size={14} />刷新历史</button></div>
        <small>父级固定调度，两个子 Agent 使用真实 LLM。研究任务读取合成事实，核验任务尝试外部投递；模型未请求工具时，不算护栏阻断。按节点保存脱敏后的任务输入、请求/响应及审计。请求头和凭据不采集；正文超限会标注截断。每秒刷新，可查询历史。</small>
      </section>
    </CollapsibleIntro>
    <div className="trace-content">
      {error && <p role="alert" className="message-error">{error}</p>}
      <label className="trace-history">执行历史<select aria-label="执行历史" value={traceId} onChange={event => { setTrace(null); setTraceId(event.target.value); }}><option value="" disabled>选择一条链路</option>{!history.some(item => item.traceId === traceId) && traceId && <option value={traceId}>{traceId} · 当前任务</option>}{history.map(item => <option key={item.traceId} value={item.traceId}>{new Date(item.createdAt).toLocaleString()} · {statusLabel[item.status]} · {item.traceId.slice(0, 8)}</option>)}</select></label>
      {!trace ? <p className="trace-empty">{traceId ? '正在加载执行链路…' : '运行演示，或选择一条已保存的链路。'}</p> : <>
        <div className="trace-summary"><strong>{statusLabel[trace.status]}</strong><code>traceId: {trace.traceId}</code><span>事实读取 {trace.effects.factReads || 0} 次 · 实际投递 {trace.effects.deliveries || 0} 次 · 拒绝节点 {trace.spans.filter(node => node.status === 'blocked').length} 个</span></div>
        <div className="trace-workspace"><section aria-label="执行树"><h2>执行树</h2><SpanTree nodes={trace.spans} parent={null} selected={selected} onSelect={setSelected} /></section>
          <section className="trace-inspector" aria-label="节点详情"><h2>节点详情</h2>{node && <>
            <h3>{node.name}</h3><span className={`trace-status ${node.status}`}>{statusLabel[node.status]}</span>
            <details className="trace-identifiers"><summary>执行标识 · Agent / Run / Span</summary><dl>{Object.entries({ agentId: node.agentId, agentInstanceId: node.agentInstanceId, runId: node.runId, parentRunId: node.parentRunId, spanId: node.spanId, parentSpanId: node.parentSpanId }).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value || '—'}</dd></div>)}</dl></details>
            {node.reasonCode && <p className="guard-blocked">{node.reasonCode}</p>}
            <section className="trace-payloads" aria-label="节点请求与响应">
              <h3>请求与响应 · {payloads.length}</h3>
              <p>HTTP 正文来自实际接口调用；框架消息用于补充。执行结果中的 executed=false 表示工具未进入，不代表真实工具返回了业务结果。</p>
              {!payloads.length && <p>{node.status === 'running' ? '等待采集请求与响应…' : node.kind === 'delegate' ? '派生动作不直接请求模型，请选择其下游 Agent、模型或工具节点。' : '该历史节点未采集正文，请重新运行一轮演示。'}</p>}
              {payloads.map((payload, index) => <details key={`${selected}-${index}`} className="trace-payload">
                <summary>{payloadLabels[payload.kind] || payload.kind}{payload.truncated ? ' · 已截断' : ''}</summary>
                {payload.truncated && <p className="payload-truncated">正文超过采集限制，显示脱敏后 JSON 的文本片段；截断前 {payload.originalBytes.toLocaleString()} 字节。</p>}
                <button type="button" className="payload-expand" onClick={() => setInspected(payload)}>大窗口查看 · {payloadLabels[payload.kind] || payload.kind}</button>
                <pre><code>{payloadText(payload)}</code></pre>
              </details>)}
            </section>
            <h3>该节点的治理审计 · {audits.length}</h3>
            {!audits.length && <p>该节点暂无直接关联审计；工具、模型及 Agent 节点分别记录。</p>}
            {audits.map(row => <details key={row.sequence}><summary>{row.point} · {row.verdict}{row.reasonCode ? ` · ${row.reasonCode}` : ''}</summary><pre>{JSON.stringify(row, null, 2)}</pre></details>)}
          </>}</section></div>
        <section className="trace-timeline" aria-label="执行时间线"><h2>执行时间线 <small>相对根任务开始时间（毫秒）</small></h2>{trace.spans.map(item => <button type="button" key={item.spanId} onClick={() => setSelected(item.spanId)} aria-pressed={selected === item.spanId}>
          <span>{item.name}</span><div className="timeline-track"><i className={item.status} style={{ marginLeft: `${(item.startNanos - first) / (end - first) * 100}%`, width: `${Math.max(0.7, ((item.endNanos || end) - item.startNanos) / (end - first) * 100)}%` }} /></div><small>{Math.round((item.startNanos - first) / 1e6)} → {item.endNanos ? Math.round((item.endNanos - first) / 1e6) : '运行中'} ms</small>
        </button>)}</section>
      </>}
    </div>
    <SourceDialog open={inspected !== null} onClose={() => setInspected(null)} title={inspected ? payloadLabels[inspected.kind] || inspected.kind : '请求与响应'} closeLabel="关闭请求与响应弹窗">
      {inspected && <>{inspected.truncated && <p className="payload-truncated">已截断；截断前 {inspected.originalBytes.toLocaleString()} 字节。显示的是 JSON 文本片段。</p>}<pre><code>{payloadText(inspected)}</code></pre></>}
    </SourceDialog>
  </main>;
}
