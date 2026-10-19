'use client';

import { useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
import { ChevronRight, FolderOpen, MessageSquare, ShieldCheck, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { SimpleChat, type Example } from '@/components/simple-chat';
import { Asi02Chat, type CaseEntry } from '@/components/asi02-chat';
import type { StreamingCase } from '@/lib/streaming-demo';

const StreamingDemo = dynamic(() => import('@/components/streaming-demo').then(module => module.StreamingDemo));

type RiskCategory = { id: string; name: string; englishName: string; description: string; cases: CaseEntry[] };

const AdvancedChat = dynamic(() => import('@/components/advanced-chat').then(module => module.AdvancedChat), {
  loading: () => <main className="catalog-loading">正在打开完整 Agent…</main>,
});

export default function Page() {
  const [examples, setExamples] = useState<Example[]>([]);
  const [streamingCases, setStreamingCases] = useState<StreamingCase[]>([]);
  const [risks, setRisks] = useState<RiskCategory[]>([]);
  const [selected, setSelected] = useState('plain-chat');
  const [busy, setBusy] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.all([fetch('/api/examples'), fetch('/api/examples/risks'), fetch('/api/examples/streaming')])
      .then(async ([response, riskResponse, streamingResponse]) => {
        if (!active) return;
        if (!response.ok || !riskResponse.ok || !streamingResponse.ok) throw new Error('无法加载案例，请确认 Spring Boot 已启动。');
        const [examples, risks, streams] = await Promise.all([response.json(), riskResponse.json(), streamingResponse.json()]);
        if (active) { setExamples(examples); setRisks(risks); setStreamingCases(streams); }
      })
      .catch(error => { if (active) setError(error instanceof Error ? error.message : String(error)); });
    return () => { active = false; };
  }, []);

  const activeRisk = risks.find(risk => risk.cases.some(item => `${risk.id}:${item.id}` === selected));
  const activeCase = activeRisk?.cases.find(item => `${activeRisk.id}:${item.id}` === selected);
  const streamingCase = streamingCases.find(item => `streaming:${item.id}` === selected);
  const example = examples.find(item => item.id === selected);
  return <div className={`catalog-shell${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
    <aside className="catalog-sidebar" aria-label="案例侧栏">
      <div className="catalog-sidebar-heading"><a className="brand" href="/"><span className="brand-icon"><ShieldCheck size={23} /></span><div>AGT<span>EXAMPLES / 从简单开始</span></div></a>
      <button className="sidebar-toggle" type="button" aria-label={sidebarCollapsed ? '展开目录' : '收起目录'} title={sidebarCollapsed ? '展开目录' : '收起目录'} aria-expanded={!sidebarCollapsed} aria-controls="case-navigation" onClick={() => setSidebarCollapsed(value => !value)}>{sidebarCollapsed ? <PanelLeftOpen size={19} /> : <PanelLeftClose size={19} />}<span className="sidebar-toggle-label">{sidebarCollapsed ? '展开目录' : '收起目录'}</span></button></div>
      <div className="catalog-sidebar-body" id="case-navigation" hidden={sidebarCollapsed}>
      <div className="catalog-intro">对照代码，试一次对话。</div>
      <nav aria-label="对话案例目录">
        {['原生 LangChain4j', '安全护栏入门'].map(folder => <details className="example-folder" key={folder}>
          <summary><ChevronRight size={14} className="folder-chevron" /><FolderOpen size={16} />{folder}</summary>
          <div className="folder-cases">{examples.filter(item => item.folder === folder).map(item => <button key={item.id} disabled={busy} aria-current={selected === item.id ? 'page' : undefined} onClick={() => setSelected(item.id)}><MessageSquare size={14} />{item.name}</button>)}</div>
        </details>)}
        <details className="example-folder"><summary><ChevronRight size={14} className="folder-chevron" /><ShieldCheck size={16} />流式护栏实验<span className="case-count">{streamingCases.length}</span></summary><div className="folder-cases">{streamingCases.map(item => <button key={item.id} disabled={busy} aria-current={selected === `streaming:${item.id}` ? 'page' : undefined} onClick={() => setSelected(`streaming:${item.id}`)}><MessageSquare size={14} />{item.name}</button>)}</div></details>
        {risks.map(risk => <details className="example-folder" key={risk.id}>
          <summary title={`${risk.englishName} · ${risk.description}`}><ChevronRight size={14} className="folder-chevron" /><ShieldCheck size={16} />{risk.name}<span className="case-count">{risk.cases.length}</span></summary>
          <div className="folder-cases">{risk.cases.map(item => <button key={item.id} disabled={busy} aria-current={selected === `${risk.id}:${item.id}` ? 'page' : undefined} onClick={() => setSelected(`${risk.id}:${item.id}`)}><MessageSquare size={14} />{item.name}</button>)}</div>
        </details>)}
        <details className="example-folder"><summary><ChevronRight size={14} className="folder-chevron" /><FolderOpen size={16} />完整 Agent</summary><div className="folder-cases"><button disabled={busy} aria-current={selected === 'advanced' ? 'page' : undefined} onClick={() => setSelected('advanced')}><MessageSquare size={14} />工作区实验台</button></div></details>
      </nav>
      <p className="catalog-foot">入门案例每次独立问答。<br />文件、审批与运行观察在完整 Agent 中。</p>
      </div>
    </aside>
    {streamingCase ? <StreamingDemo key={streamingCase.id} entry={streamingCase} onBusyChange={setBusy} /> : selected === 'advanced' ? <AdvancedChat onBusyChange={setBusy} /> : activeCase && activeRisk ? <Asi02Chat key={`${activeRisk.id}:${activeCase.id}`} category={activeRisk.id} categoryTitle={activeRisk.name} entry={activeCase} onBusyChange={setBusy} /> : example
      ? <SimpleChat key={example.id} example={example} onBusyChange={setBusy} />
      : <main className="catalog-loading" role="status">{error || '正在加载案例…'}{error && <button onClick={() => window.location.reload()}>重新加载</button>}</main>}
  </div>;
}
