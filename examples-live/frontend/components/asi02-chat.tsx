'use client';

import { useEffect, useRef, useState } from 'react';
import { Code2, Play, ShieldCheck } from 'lucide-react';
import { Markdown } from '@/components/markdown';
import { CollapsibleIntro } from '@/components/collapsible-intro';
import { ExperimentTrace } from '@/components/experiment-trace';
import { observationLabel as asi02Label, type Observation } from '@/lib/asi02';
import { observationLabel as asi05Label } from '@/lib/asi05';

export type CaseEntry = { id: string; name: string; description: string; prompt: string; expected: string; source: string; note: string; approval: boolean };
type Comparison = { caseId: string; approval: string; original: Observation; guarded: Observation };
type Source = { code: string; rules: string; runner: string };
type Turn = { id: string; question: string; request: { path: string; body: { message: string; approval: string } }; result?: Comparison; error?: string };
const metricLabels: Record<string, string> = { toolEntered: '工具体进入次数', remainingCustomers: '剩余客户', readResult: '文档读取结果', sentMessages: '已发送邮件', outbox: '模拟发件箱', simulatedCredits: '模拟积分消耗', noteReads: '内部便签读取次数', externalMessages: '外传消息数', externalInbox: '外部模拟接收器', ledgerEntries: '付款记账条数', ledger: '模拟账本', ticketReads: '工单读取次数', serviceRequests: '服务实际访问次数', circuitOpen: '熔断是否打开', receiverRequests: '接收器实际请求数', fileContent: '临时文件内容', executionAttempts: '命令执行尝试', completedExecutions: '命令完成次数', outsideContent: '工作区外合成文件', workspaceContent: '工作区报告内容', capturedOutput: '命令返回内容', deadlineExceeded: '触发运行期限', backendQuarantined: '后端已拒绝复用' };
const display = (value: unknown) => typeof value === 'object' ? JSON.stringify(value) : String(value ?? '—');

function Evidence({ title, caseId, guarded, observation, category }: { category: 'asi02' | 'asi05'; title: string; caseId: string; guarded: boolean; observation: Observation }) {
  const triggered = observation.requestedCalls.length > 0;
  const label = (category === 'asi05' ? asi05Label : asi02Label)(caseId, observation, guarded);
  const keys = [...new Set([...Object.keys(observation.before), ...Object.keys(observation.after)])];
  return <section className={`evidence-card ${observation.status}`}>
    <div className="evidence-heading"><h3>{title}</h3><span>{label}</span></div>
    <p className="evidence-count">实际模型请求 {observation.modelCalls} 次 · 模型请求工具 {observation.requestedCalls.length} 次</p>
    {observation.code && <code className="evidence-code">{observation.code}</code>}
    <ExperimentTrace steps={observation.trace ?? []} />
    <div className="table-scroll"><table className="evidence-table"><caption>实际业务状态</caption><thead><tr><th>指标</th><th>执行前</th><th>执行后</th></tr></thead><tbody>
      {keys.map(key => <tr key={key}><th scope="row" title={key}>{metricLabels[key] || key}</th><td>{display(observation.before[key])}</td><td>{display(observation.after[key])}</td></tr>)}
    </tbody></table></div>
    {!triggered && observation.status === 'done' && <p className="evidence-note">模型没有提出工具调用，不能据此判断攻击复现或防护成功。</p>}
    <details><summary>模型回答</summary><Markdown text={observation.answer} /></details>
    <details><summary>模型实际请求的工具 · {observation.requestedCalls.length}</summary><pre>{JSON.stringify(observation.requestedCalls, null, 2)}</pre></details>
    <details><summary>治理审计 · {observation.audit.length}{title.startsWith('原生') ? '（未接入）' : ''}</summary><pre>{JSON.stringify(observation.audit, null, 2)}</pre></details>
  </section>;
}

export function Asi02Chat({ entry, onBusyChange, category = 'asi02' }: { entry: CaseEntry; onBusyChange: (busy: boolean) => void; category?: 'asi02' | 'asi05' }) {
  const categoryName = category === 'asi05' ? 'ASI05 意外代码执行' : 'ASI02 工具滥用';
  const [message, setMessage] = useState(entry.prompt);
  const [approval, setApproval] = useState('deny');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);
  const [sourceOpen, setSourceOpen] = useState(false);
  const [source, setSource] = useState<Source | null>(null);
  const [sourceError, setSourceError] = useState('');
  const pending = useRef<AbortController | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { onBusyChange(busy); }, [busy, onBusyChange]);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }); }, [turns]);
  useEffect(() => {
    if (!sourceOpen || source) return;
    let active = true;
    setSourceError('');
    fetch(`/api/examples/${category}/${entry.id}/source`).then(async response => {
      if (!response.ok) throw new Error('无法读取源码，请重新展开。');
      const result = await response.json();
      if (active) setSource(result);
    }).catch(error => { if (active) setSourceError(error instanceof Error ? error.message : String(error)); });
    return () => { active = false; };
  }, [sourceOpen, source, entry.id, category]);

  async function run() {
    if (pending.current || !message.trim()) return;
    const id = crypto.randomUUID();
    const request = { path: `/api/examples/${category}/${entry.id}/run`, body: { message, approval } };
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setTurns(previous => [...previous.slice(-9), { id, question: message, request }]);
    try {
      const response = await fetch(request.path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request.body), signal: controller.signal,
      });
      if (!response.ok) throw new Error(`案例请求失败（${response.status}），请确认后端已更新。`);
      const result: Comparison = await response.json();
      setTurns(previous => previous.map(turn => turn.id === id ? { ...turn, result } : turn));
    } catch (error) {
      if (!controller.signal.aborted) setTurns(previous => previous.map(turn => turn.id === id
        ? { ...turn, error: error instanceof Error ? error.message : '请求失败' } : turn));
    } finally { pending.current = null; setBusy(false); }
  }

  return <main className="main simple-main asi02-main">
    <CollapsibleIntro eyebrow={`${categoryName} / ${entry.name.slice(0, 2)}`} title={entry.name.slice(5)} badge={<span className="example-tag guarded"><ShieldCheck size={14} />原生 / 防护对照</span>}>
    <section className="lesson-intro"><p>{entry.description}</p>
      <div className="lesson-actions"><button disabled={busy || !message.trim()} onClick={() => void run()}><Play size={14} />运行真实 LLM 对照</button><button onClick={() => setSourceOpen(value => !value)} aria-expanded={sourceOpen}><Code2 size={15} />{sourceOpen ? '收起代码' : '查看案例代码'}</button></div>
      <div className="lesson-expected"><strong>触发后的预期观察</strong><p>{entry.expected}</p></div>
      <small>{entry.note}</small><small>两条路径分别请求已配置的真实 LLM，业务状态各自重置。模型可能拒绝或改写请求；以工具调用与状态证据为准，不保证每次都触发。</small>
      {entry.id === 'late-rejection' && <p className="asi02-caution">这是反例：即使显示拒绝，文件仍可能已被修改。请查看 fileContent。</p>}
    </section>
    {sourceOpen && <section className="source-panel" aria-label={`${categoryName} 案例源码`}>{sourceError ? <p role="alert">{sourceError}</p> : !source ? <p>正在读取源码…</p> : <>
      <div className="source-filename">{entry.source}</div><pre><code>{source.code}</code></pre>
      <details><summary>共用执行与参数约束 · {category === 'asi05' ? 'ExecutionCase.java' : 'Rules.java'}</summary><pre><code>{source.rules}</code></pre></details>
      <details><summary>原生与 Starter 接线 · ExperimentRunner.java</summary><pre><code>{source.runner}</code></pre></details>
    </>}</section>}
    </CollapsibleIntro>
    <div className="conversation asi02-conversation" aria-live="polite" aria-busy={busy}>
      {!turns.length && <div className="simple-empty"><ShieldCheck size={28} /><h2>看工具做了什么。</h2><p>运行同一任务，比较工具执行和业务状态。<br />{category === 'asi05' ? '只执行固定实验脚本；文件与接收器均为本轮临时资源。' : '不需要连接真实数据库、邮件或支付服务。'}</p></div>}
      {turns.map(turn => <article className="comparison-turn" key={turn.id}><p className="comparison-question">{turn.question}</p>
        <details className="experiment-request"><summary>本轮网页请求 · POST {turn.request.path}</summary><pre><code>{JSON.stringify(turn.request.body, null, 2)}</code></pre></details>
        {turn.error ? <p className="message-error" role="alert">{turn.error}</p> : turn.result
          ? <div className="evidence-pair"><Evidence title="原生 LangChain4j" category={category} caseId={entry.id} guarded={false} observation={turn.result.original} /><Evidence title="接入防护" category={category} caseId={entry.id} guarded observation={turn.result.guarded} /></div>
          : <div className="thinking"><span /><span /><span /><small>正在依次运行两条真实模型路径，可能需要多轮工具调用…</small></div>}
      </article>)}<div ref={end} />
    </div>
    <footer className="composer-wrap"><form className="composer" onSubmit={event => { event.preventDefault(); void run(); }}>
      <textarea aria-label={`${categoryName} 实验提问`} value={message} disabled={busy} onChange={event => setMessage(event.target.value)} maxLength={16000} rows={3} />
      <div className="asi02-composer-bottom"><button type="button" disabled={busy} onClick={() => setMessage(entry.prompt)}>恢复推荐提问</button>
        {entry.approval && <label>模拟审批决定 <select aria-label="模拟审批决定" value={approval} disabled={busy} onChange={event => setApproval(event.target.value)}><option value="deny">拒绝本次动作</option><option value="allow">批准本轮首个合法动作</option></select></label>}
        <button type="submit" disabled={busy || !message.trim()}>{busy ? '运行中…' : '运行对照'}</button>
      </div>
    </form><div className="composer-hint"><span>每次运行请求真实 LLM · API Key 仅在后端</span><button disabled={busy} onClick={() => setTurns([])}>清空展示</button></div></footer>
  </main>;
}
