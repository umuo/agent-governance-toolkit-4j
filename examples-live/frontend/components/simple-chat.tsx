'use client';

import { useEffect, useRef, useState } from 'react';
import { ArrowUp, Code2, MessageSquare, ShieldCheck } from 'lucide-react';
import { Markdown } from '@/components/markdown';

export type Example = { id: string; folder: string; name: string; description: string; prompt: string; expected: string; source: string; guarded: boolean };
type Reply = { status: 'done' | 'blocked' | 'error'; answer: string; code: string };
type Turn = { id: string; question: string; reply?: Reply; error?: string };
type Source = { code: string; configuration: string };

export function SimpleChat({ example, onBusyChange }: { example: Example; onBusyChange: (busy: boolean) => void }) {
  const [prompt, setPrompt] = useState('');
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
    fetch(`/api/examples/${example.id}/source`)
      .then(async response => {
        if (!active) return;
        if (!response.ok) throw new Error('源码读取失败，请重新打开代码。');
        const source = await response.json();
        if (active) setSource(source);
      })
      .catch(error => { if (active) setSourceError(error instanceof Error ? error.message : String(error)); });
    return () => { active = false; };
  }, [sourceOpen, source, example.id]);

  async function send(message: string) {
    if (pending.current || !message.trim()) return;
    const id = crypto.randomUUID();
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true); setPrompt('');
    setTurns(previous => [...previous.slice(-49), { id, question: message.trim() }]);
    try {
      const response = await fetch(`/api/examples/${example.id}/chat`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message.trim() }), signal: controller.signal,
      });
      if (!response.ok) throw new Error(response.status === 502
        ? '模型调用失败，请检查后端的 LLM_API_KEY、LLM_MODEL、LLM_BASE_URL；工具案例需要模型支持工具调用。'
        : `请求失败（${response.status}），请检查后端服务。`);
      const reply: Reply = await response.json();
      setTurns(previous => previous.map(turn => turn.id === id ? { ...turn, reply } : turn));
    } catch (error) {
      if (!controller.signal.aborted) setTurns(previous => previous.map(turn => turn.id === id
        ? { ...turn, error: error instanceof Error ? error.message : '请求失败' } : turn));
    } finally {
      pending.current = null; setBusy(false);
    }
  }

  return <main className="main simple-main">
    <header className="header"><div><span className="eyebrow">{example.folder} / {example.name.slice(0, 2)}</span><h1>{example.name.slice(5)}</h1></div><span className={`example-tag ${example.guarded ? 'guarded' : ''}`}>{example.guarded ? <ShieldCheck size={14} /> : <MessageSquare size={14} />}{example.guarded ? 'AGT 护栏' : '原生 · 无 AGT 护栏'}</span></header>
    <section className="lesson-intro">
      <p>{example.description}</p>
      <div className="lesson-actions"><button onClick={() => void send(example.prompt)} disabled={busy}>运行推荐提问 <span>↗</span></button><button onClick={() => setSourceOpen(previous => !previous)} aria-expanded={sourceOpen}><Code2 size={15} />{sourceOpen ? '收起代码' : '查看最简代码'}</button></div>
      <div className="lesson-expected"><strong>推荐提问</strong><p>{example.prompt}</p><strong>预期观察</strong><p>{example.expected}</p></div>
      {example.guarded && <small>这三个入门案例共用输入拦截、演示号码脱敏和工具权限规则。输入匹配与号码替换都是字面规则。</small>}
    </section>
    {sourceOpen && <section className="source-panel" aria-label="案例源码">
      {sourceError ? <p role="alert">{sourceError}</p> : !source ? <p role="status">正在读取源码…</p> : <>
        <div className="source-filename">{example.source}</div><pre><code>{source.code}</code></pre>
        {source.configuration && <details><summary>共用规则与 Starter 配置 · SimpleExamplesConfiguration.java</summary><pre><code>{source.configuration}</code></pre></details>}
      </>}
    </section>}
    <div className="conversation simple-conversation" aria-live="polite" aria-busy={busy}>
      {!turns.length && <div className="simple-empty"><MessageSquare size={28} /><h2>一个案例，只看一个重点。</h2><p>运行推荐提问，或输入自己的问题。<br />每次请求独立，不携带之前的对话。</p></div>}
      {turns.map(turn => <article className="turn" key={turn.id}>
        <div className="user-message"><span className="avatar user-avatar">你</span><div><span className="message-label">提问</span><p>{turn.question}</p></div></div>
        <div className="agent-message"><span className="avatar agent-avatar">AI</span><div className="agent-content">
          <div className="message-heading"><span className="message-label">{turn.reply?.status === 'blocked' ? '护栏拦截' : '回答'}</span><span className="status">{example.guarded ? 'AGT' : 'LangChain4j'}</span></div>
          {turn.error ? <p className="message-error" role="alert">{turn.error}</p>
            : turn.reply ? turn.reply.status === 'blocked' ? <div className="guard-blocked"><ShieldCheck size={17} /><div>{turn.reply.answer}<code>{turn.reply.code}</code></div></div>
              : <Markdown text={turn.reply.answer} />
            : <div className="thinking"><span /><span /><span /><small>等待本次回答…</small></div>}
        </div></div>
      </article>)}<div ref={end} />
    </div>
    <footer className="composer-wrap"><form className="composer" onSubmit={event => { event.preventDefault(); void send(prompt); }}>
      <textarea aria-label="案例提问" value={prompt} onChange={event => setPrompt(event.target.value)} placeholder="输入问题，或点击「运行推荐提问」…" disabled={busy} maxLength={16000} rows={2}
        onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(prompt); } }} />
      <div className="composer-bottom"><span>{busy ? '本次问答进行中…' : '独立问答 · 不保存上下文'}</span><button className="send" type="submit" disabled={busy || !prompt.trim()} aria-label="发送消息"><ArrowUp size={18} /></button></div>
    </form><div className="composer-hint"><span>Enter 发送 · Shift + Enter 换行</span><button disabled={busy || !turns.length} onClick={() => setTurns([])}>清空页面</button></div></footer>
  </main>;
}
