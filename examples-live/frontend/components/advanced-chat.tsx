'use client';

import { useEffect, useRef, useState } from 'react';
import { CollapsibleIntro } from '@/components/collapsible-intro';
import { Markdown } from '@/components/markdown';
import { type Tool } from '@/components/tool-card';
import { ExecutionProcess, type Approval } from '@/components/execution-process';
import { appendModelDelta, appendReference, modelContent, type Activity } from '@/lib/process';
import { ArrowUp, Circle, Code2, FileText, ShieldCheck, Square, Terminal } from 'lucide-react';
import { consumeEvents, type AgentEvent } from '@/lib/stream';

type Turn = { id: string; question: string; text: string; activity: Activity[]; finalCall?: number; tools: Tool[]; approvals: Approval[]; status: 'running' | 'done' | 'error' | 'cancelled'; error?: string; budget?: Record<string, unknown> };
type ActiveRun = { ready: Promise<void>; id: string; detached: boolean; cancelled: boolean; stopPromise?: Promise<void> };
type Config = { configured: boolean; model: string; bashEnabled: boolean; bashHostReads: boolean };
const value = (input: unknown) => typeof input === 'string' ? input : '';
const profiles = [{ id: 'agent', label: '通用 Agent' }, { id: 'read-only', label: '只读权限' }, { id: 'approval', label: '写入审批' }, { id: 'budget', label: '有限预算' }];

export function AdvancedChat({ onBusyChange }: { onBusyChange: (busy: boolean) => void }) {
  const [config, setConfig] = useState<Config | null>(null);
  const [profile, setProfile] = useState('agent');
  const [mode, setMode] = useState('preview');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [events, setEvents] = useState<{ type: string; data: Record<string, unknown>; time: string; id: string }[]>([]);
  const [now, setNow] = useState(Date.now());
  const activeRun = useRef<ActiveRun | null>(null);
  const [stopping, setStopping] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  const followOutput = useRef(true);
  const composer = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    let active = true;

    fetch('/api/agent/config')
      .then(async a => {
        if (!active) return;
        if (!a.ok) throw new Error('无法连接 Agent 服务，请先启动 Spring Boot。');
        const configData = await a.json();
        if (!active) return;
        setConfig(configData);
      })
      .catch(error => {
        if (active) setNotice(error instanceof Error ? error.message : String(error));
      });
    return () => {
      active = false;
      const run = activeRun.current;
      if (run) {
        run.detached = true;
        // 取消服务端任务，再由 SSE 自然关闭；不主动中断响应体读取。
        if (!run.stopPromise) run.stopPromise = cancelRun(run).then(() => { run.cancelled = true; }).catch(() => {});
      }
    };
  }, []);
  useEffect(() => { onBusyChange(busy); }, [busy, onBusyChange]);
  useEffect(() => { if (followOutput.current) end.current?.scrollIntoView({ behavior: 'instant', block: 'end' }); }, [turns]);
  useEffect(() => { if (!busy) return; const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, [busy]);

  function update(id: string, transform: (turn: Turn) => Turn) { setTurns(previous => previous.map(turn => turn.id === id ? transform(turn) : turn)); }
  function receive(id: string, event: AgentEvent) {
    const d = event.data;
    setEvents(previous => {
      const last = previous.at(-1);
      if (last && ['text_delta', 'reasoning_delta', 'tool_delta'].includes(event.type)
          && last.type === event.type && last.data.call === d.call && last.data.key === d.key) {
        const field = event.type === 'tool_delta' ? 'arguments' : 'text';
        const combined = value(last.data[field]) + value(d[field]);
        return [...previous.slice(0, -1), { ...last, data: { ...d, [field]: combined.slice(-6000),
          fragments: Number(last.data.fragments || 1) + 1, truncated: combined.length > 6000 || last.data.truncated === true } }];
      }
      return [...previous.slice(-149), { ...event, id: crypto.randomUUID(), time: new Date().toLocaleTimeString('zh-CN', { hour12: false }) }];
    });
    update(id, turn => {
      const next = { ...turn, tools: turn.tools.map(tool => ({ ...tool })), approvals: turn.approvals.map(approval => ({ ...approval })) };
      switch (event.type) {
        case 'text_delta': case 'reasoning_delta': next.activity = appendModelDelta(next.activity, event.type === 'text_delta' ? 'content' : 'reasoning', Number(d.call), value(d.text)); break;
        case 'model_complete': if (d.hasTools === false) next.finalCall = Number(d.call); break;
        case 'answer_start': next.text = ''; break;
        case 'answer_delta': next.text += value(d.text); break;
        case 'tool_delta': {
          const key = value(d.key);
          let tool = next.tools.find(item => item.key === key);
          if (!tool) { tool = { key, id: value(d.id), name: value(d.name), arguments: '', state: 'receiving' }; next.tools.push(tool); }
          tool.id = value(d.id) || tool.id; tool.name = value(d.name) || tool.name; tool.arguments += value(d.arguments);
          next.activity = appendReference(next.activity, 'tool', tool.key);
          break;
        }
        case 'tool_start': {
          let tool = next.tools.find(item => item.id === d.id);
          if (!tool) { tool = { key: value(d.id), id: value(d.id), name: value(d.name), arguments: '', state: 'running' }; next.tools.push(tool); }
          tool.state = 'running'; tool.name = value(d.name); tool.arguments = value(d.arguments); next.activity = appendReference(next.activity, 'tool', tool.key); break;
        }
        case 'tool_result': case 'tool_error': {
          let tool = next.tools.find(item => item.id === d.id);
          if (!tool && event.type === 'tool_error') { tool = { key: value(d.id), id: value(d.id), name: value(d.name), arguments: value(d.arguments), state: 'error' }; next.tools.push(tool); }
          if (tool) { next.activity = appendReference(next.activity, 'tool', tool.key); tool.state = event.type === 'tool_error' ? 'error' : 'done'; tool.result = event.type === 'tool_error' ? [value(d.code), value(d.message), value(d.hint)].filter(Boolean).join('\n') : value(d.result); tool.recoverable = d.recoverable === true; tool.stdout = typeof d.stdout === 'string' ? d.stdout : undefined; tool.stderr = typeof d.stderr === 'string' ? d.stderr : undefined; tool.exitCode = typeof d.exitCode === 'number' ? d.exitCode : undefined; tool.arguments = value(d.arguments) || tool.arguments; tool.duration = typeof d.durationMs === 'number' ? d.durationMs : undefined; }
          break;
        }
        case 'approval_required': next.approvals.push(d as unknown as Approval); next.activity = appendReference(next.activity, 'approval', value(d.id)); break;
        case 'budget': next.budget = d; break;
        case 'error': next.status = 'error'; next.tools.forEach(tool => { if (tool.state === 'receiving' || tool.state === 'running') tool.state = 'error'; }); next.error = `${value(d.code)} · ${value(d.message)}`;
          if (d.code === 'sandbox_unavailable') next.error += '\n审批已通过，但当前环境无法启动所配置的原生沙箱。批准动作不会改变沙箱隔离规则；严格模式不会自动回退为 HOST 读取。';
          if (d.code === 'sandbox_policy_unsupported') next.error += '\n当前原生后端不支持所配置的隔离策略。'; next.approvals.forEach(item => { if (!item.decision) item.decision = '运行已结束'; }); break;
        case 'cancelled': next.status = 'cancelled'; next.error = '已停止。已完成的工具副作用不会回滚。'; next.tools.forEach(tool => { if (tool.state === 'receiving' || tool.state === 'running') tool.state = 'error'; }); next.approvals.forEach(item => { if (!item.decision) item.decision = '运行已停止'; }); break;
        case 'done': next.status = 'done'; next.tools.forEach(tool => { if (tool.state === 'receiving') tool.state = 'error'; }); break;
      }
      return next;
    });
  }
  async function send() {
    if (activeRun.current || busy || !config?.configured || !prompt.trim()) return;
    const text = prompt.trim(), id = crypto.randomUUID();
    followOutput.current = true;
    setPrompt(''); setBusy(true); setNotice('');
    setTurns(previous => [...previous, { id, question: text, text: '', activity: [], tools: [], approvals: [], status: 'running' }]);
    let markReady: () => void = () => {};
    const run: ActiveRun = { ready: new Promise<void>(resolve => { markReady = resolve; }), id, detached: false, cancelled: false }; activeRun.current = run;
    let terminal = false;
    try {
      const response = await fetch('/api/agent/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: text, scenario: profile, mode }) });
      markReady();
      if (!response.ok) throw new Error(`接口返回 ${response.status}：${await response.text()}`);
      if (!response.body) throw new Error('服务未返回事件流');
      await consumeEvents(response.body, event => {
        if (event.type === 'done' || event.type === 'error' || event.type === 'cancelled') terminal = true;
        if (!run.detached) receive(id, event);
      });
      await run.stopPromise;
      if (run.detached) return;
      // 兼容旧服务端：取消已确认且流正常结束，也可明确标为停止。
      if (!terminal && run.cancelled) { receive(id, { type: 'cancelled', data: {} }); terminal = true; }
      if (!terminal) throw new Error('事件流意外中断，请重试。');
    } catch (error) {
      if (!run.detached) update(id, turn => ({ ...turn, tools: turn.tools.map(tool => ({ ...tool, state: tool.state === 'receiving' || tool.state === 'running' ? 'error' : tool.state })), status: run.cancelled ? 'cancelled' : 'error', error: run.cancelled ? '已停止。已完成的工具副作用不会回滚。' : error instanceof Error ? error.message : '连接失败' }));
    } finally {
      // 请求失败也要释放等待停止的调用；成功时则等待取消响应再开放下一轮。
      markReady();
      await run.stopPromise;
      if (activeRun.current === run) activeRun.current = null;
      if (!run.detached) { setBusy(false); setStopping(false); composer.current?.focus(); }
    }
  }
  async function cancelRun(run: ActiveRun) {
    // 必须等 chat 被后端接受，避免快速停止先于 chat 到达而取消空会话。
    await run.ready;
    await api('/cancel', 'POST');
  }
  async function stop() {
    const run = activeRun.current;
    if (!run || run.stopPromise) return;
    setStopping(true);
    run.stopPromise = cancelRun(run)
      .then(() => { run.cancelled = true; })
      .catch(error => { if (!run.detached) setNotice(error instanceof Error ? error.message : '停止请求失败，请重试'); })
      .finally(() => { if (!run.detached) setStopping(false); });
    await run.stopPromise;
    // 请求失败时允许再次停止；成功时等待 cancelled/EOF，不截断正文。
    if (!run.cancelled && activeRun.current === run) run.stopPromise = undefined;
  }
  async function api(path: string, method: string, body?: unknown) {
    const response = await fetch(`/api/agent${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) throw new Error(`操作失败 (${response.status})：${await response.text()}`);
  }
  async function reset(newProfile?: string) {
    if (busy) return;
    try { await api('/session', 'DELETE'); setTurns([]); setEvents([]); setNotice(''); if (newProfile) setProfile(newProfile); }
    catch (error) { setNotice(error instanceof Error ? error.message : '清空失败'); }
  }
  async function decide(turnId: string, approval: Approval, approved: boolean) {
    try { await api(`/approvals/${approval.id}`, 'POST', { approved }); update(turnId, turn => ({ ...turn, approvals: turn.approvals.map(item => item.id === approval.id ? { ...item, decision: approved ? '已批准' : '已拒绝' } : item) })); }
    catch (error) { setNotice(error instanceof Error ? error.message : '审批失败'); }
  }
  const latest = turns.at(-1);
  const toolCount = turns.reduce((count, turn) => count + turn.tools.filter(tool => tool.state === 'done').length, 0);

  return <div className="advanced-shell">
    <main className="main">
      <CollapsibleIntro eyebrow="AGENT LAB / PLAYGROUND" title="完整 Agent · 工作区实验台" badge={<div className="connection"><span className={config?.configured ? 'online' : 'offline'} />{config?.configured ? '模型已配置' : '等待配置'}</div>}>
      <div className="controls"><button type="button" disabled={busy} onClick={() => reset()}>新对话</button><div className="control-group"><ShieldCheck size={15} /><label htmlFor="profile">治理策略</label><select id="profile" value={profile} disabled={busy} onChange={event => reset(event.target.value)}>{profiles.map(item => <option value={item.id} key={item.id}>{item.label}</option>)}</select></div><div className="control-group"><label htmlFor="mode">输出模式</label><select id="mode" value={mode} disabled={busy} onChange={event => setMode(event.target.value)}><option value="strict">严格检查后交付</option><option value="preview">完整过程实时渲染</option></select></div><span className="model-name">{config?.model || '尚未配置模型'}</span></div>
      {mode === 'preview' && <div className="preview-notice">实时展示每轮 response content、接口实际返回的 reasoning 和工具调用。过程输出尚未通过完整检查，结束或失败后仍保留供查看；工具执行始终受授权控制。</div>}
      </CollapsibleIntro>
      {notice && <div className="notice" role="alert">{notice}</div>}
      {config && !config.configured && <div className="notice" role="alert">请填写 examples-live/.env.local 的 LLM_API_KEY、LLM_MODEL 和 AGT_WORKSPACE，然后重启后端。</div>}
      <div className="conversation" aria-live="polite" aria-busy={busy} onScroll={event => { const node = event.currentTarget; followOutput.current = node.scrollHeight - node.scrollTop - node.clientHeight < 100; }}>
        {turns.length === 0 && <section className="welcome"><div className="welcome-symbol"><Code2 size={29} /><span><ShieldCheck size={17} /></span></div><span className="eyebrow">YOUR AGENT. YOUR RULES.</span><h2>从一个任务开始。</h2><p>与真实 LLM 协作，让 Agent 读写文件、执行工具。<br />在同一条时间线上，观察每一步治理决策。</p><div className="suggestions">{[{ icon: FileText, title: '创建并验证文件', prompt: '创建 hello.md，写一段介绍 Agent 治理的 Markdown，然后读取文件验证。' }, { icon: ShieldCheck, title: '试试人工审批', prompt: '创建 approved.txt，内容是 approved，然后读取确认。', profile: 'approval' }, { icon: Terminal, title: '探索工作区', prompt: '列出当前工作区中的所有文件，并说明你可以使用哪些工具。' }].map(item => <button key={item.title} onClick={async () => { if (item.profile) await reset(item.profile); setPrompt(item.prompt); composer.current?.focus(); }}><item.icon size={19} /><span>{item.title}</span><span>↗</span></button>)}</div><div className="capabilities"><span>readFile</span><span>writeFile</span><span>editFile</span><span>searchFile</span><span>Bash {config?.bashEnabled ? '· 已启用' : '· 未启用'}</span></div></section>}
        {turns.map(turn => <article className="turn" key={turn.id}>
          <div className="user-message"><span className="avatar user-avatar">U</span><div><span className="message-label">你</span><p>{turn.question}</p></div></div>
          <div className="agent-message"><span className="avatar agent-avatar"><ShieldCheck size={19} /></span><div className="agent-content"><div className="message-heading"><span className="message-label">AGT Agent</span><span className={`status ${turn.status}`}>{({ running: '运行中', done: '已完成', error: '已中止', cancelled: '已停止' })[turn.status]}</span></div>
            <ExecutionProcess activity={turn.activity} tools={turn.tools} approvals={turn.approvals} now={now} running={turn.status === 'running'} checkedText={turn.status === 'done' ? turn.text : ''} finalCall={turn.finalCall} onDecide={(approval, approved) => void decide(turn.id, approval, approved)} />
            {turn.text && (!modelContent(turn.activity, turn.finalCall) || turn.status === 'done' && modelContent(turn.activity, turn.finalCall) !== turn.text) && <div className="checked-answer"><span className="process-label">最终答复 · 已检查</span><Markdown text={turn.text} /></div>}
            {turn.status === 'running' && !turn.text && <div className="thinking"><span /><span /><span /><small>{turn.approvals.some(approval => !approval.decision) ? '等待审批' : turn.tools.some(tool => tool.state === 'running') ? '工具执行中' : 'Agent 正在继续执行'}</small></div>}
            {turn.error && <div className="message-error" role="alert">{turn.error}</div>}
            {turn.budget && <div className="budget"><span>{String(turn.budget.modelCalls)} 次模型调用</span><span>{String(turn.budget.toolCalls)} 次工具调用</span><span>{turn.budget.tokens == null ? '未知' : String(turn.budget.tokens)} tokens</span></div>}
          </div></div>
        </article>)}<div ref={end} />
      </div>
      <footer className="composer-wrap"><form className="composer" onSubmit={event => { event.preventDefault(); void send(); }}><textarea ref={composer} aria-label="给 Agent 的任务" placeholder="给 Agent 一个任务…" value={prompt} onChange={event => setPrompt(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} rows={2} disabled={busy} maxLength={16000} /><div className="composer-bottom"><span><ShieldCheck size={13} /> 工具执行始终经过 AGT</span>{busy ? <button type="button" className="send stop" onClick={stop} disabled={stopping} aria-label="停止运行"><Square size={15} fill="currentColor" /> {stopping ? '正在停止…' : '停止'}</button> : <button type="submit" className="send" disabled={!prompt.trim() || !config?.configured} aria-label="发送消息"><ArrowUp size={19} /></button>}</div></form><div className="composer-hint"><span>Enter 发送 · Shift + Enter 换行</span><span>本地实验环境 · 副作用不会自动回滚</span></div></footer>
    </main>

    <aside className="inspector"><div className="inspector-title"><span><Circle size={13} /> 运行观察</span><span className="live-label">LIVE</span></div><div className="metrics"><div><span>已完成回合</span><strong>{turns.filter(turn => turn.status === 'done').length.toString().padStart(2, '0')}</strong></div><div><span>已执行工具</span><strong>{toolCount.toString().padStart(2, '0')}</strong></div></div><div className="inspector-section"><span className="micro">RUNTIME BOUNDARIES</span><dl><div><dt>工作区</dt><dd>浏览器会话独立</dd></div><div><dt>权限来源</dt><dd>可信宿主</dd></div><div><dt>Bash</dt><dd>{config?.bashEnabled ? config.bashHostReads ? 'HOST 读取（显式开启）' : 'workspace + 系统运行时只读' : '未启用'}</dd></div><div><dt>输出</dt><dd>{mode === 'strict' ? '检查通过后交付' : '未检查实时预览'}</dd></div></dl></div><div className="timeline-header"><span className="micro">EVENT TIMELINE</span><span>{events.length}</span></div><div className="event-timeline">{events.length === 0 ? <div className="timeline-empty"><span className="timeline-line" /><p>还没有运行事件</p><small>发起任务后，在这里查看<br />模型、工具与策略决策。</small></div> : events.map(event => <details key={event.id} className={`event-row ${event.type === 'error' || event.data.verdict === 'ERROR' || (event.data.verdict === 'DENY' && event.data.approval !== 'approved') ? 'denied' : ''}`}><summary><span className="event-dot" /><div><span>{event.type === 'governance' ? value(event.data.point) : event.type}</span><small>{event.type === 'governance' ? value(event.data.reason) : event.time}</small></div></summary><pre>{JSON.stringify(event.data, null, 2)}</pre></details>)}</div><div className="inspector-foot"><ShieldCheck size={16} /><span>每个动作，均有审计记录。<br /><small>{latest?.status === 'running' ? '正在接收运行事件…' : '等待下一次运行'}</small></span></div></aside>
  </div>;
}
