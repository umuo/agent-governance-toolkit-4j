'use client';

import { useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
import { ChevronRight, FolderOpen, MessageSquare, ShieldCheck, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { SimpleChat, type Example } from '@/components/simple-chat';
import { Asi02Chat, type CaseEntry } from '@/components/asi02-chat';

const AdvancedChat = dynamic(() => import('@/components/advanced-chat').then(module => module.AdvancedChat), {
  loading: () => <main className="catalog-loading">正在打开完整 Agent…</main>,
});

export default function Page() {
  const [examples, setExamples] = useState<Example[]>([]);
  const [cases, setCases] = useState<CaseEntry[]>([]);
  const [executionCases, setExecutionCases] = useState<CaseEntry[]>([]);
  const [selected, setSelected] = useState('plain-chat');
  const [busy, setBusy] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.all([fetch('/api/examples'), fetch('/api/examples/asi02'), fetch('/api/examples/asi05')])
      .then(async ([response, caseResponse, executionResponse]) => {
        if (!active) return;
        if (!response.ok || !caseResponse.ok || !executionResponse.ok) throw new Error('无法加载案例，请确认 Spring Boot 已启动。');
        const [examples, cases, executions] = await Promise.all([response.json(), caseResponse.json(), executionResponse.json()]);
        if (active) { setExamples(examples); setCases(cases); setExecutionCases(executions); }
      })
      .catch(error => { if (active) setError(error instanceof Error ? error.message : String(error)); });
    return () => { active = false; };
  }, []);

  const activeExecution = executionCases.find(item => `asi05:${item.id}` === selected);
  const activeCase = cases.find(item => `asi02:${item.id}` === selected);
  const example = examples.find(item => item.id === selected);
  return <div className={`catalog-shell${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
    <aside className="catalog-sidebar" aria-label="案例侧栏">
      <div className="catalog-sidebar-heading"><a className="brand" href="/"><span className="brand-icon"><ShieldCheck size={23} /></span><div>AGT<span>EXAMPLES / 从简单开始</span></div></a>
      <button className="sidebar-toggle" type="button" aria-label={sidebarCollapsed ? '展开目录' : '收起目录'} title={sidebarCollapsed ? '展开目录' : '收起目录'} aria-expanded={!sidebarCollapsed} aria-controls="case-navigation" onClick={() => setSidebarCollapsed(value => !value)}>{sidebarCollapsed ? <PanelLeftOpen size={19} /> : <PanelLeftClose size={19} />}<span className="sidebar-toggle-label">{sidebarCollapsed ? '展开目录' : '收起目录'}</span></button></div>
      <div className="catalog-sidebar-body" id="case-navigation" hidden={sidebarCollapsed}>
      <div className="catalog-intro">对照代码，试一次对话。</div>
      <nav aria-label="对话案例目录">
        {['原生 LangChain4j', '安全护栏入门', 'ASI02 工具滥用', 'ASI05 意外代码执行', '完整 Agent'].map(folder => <details className="example-folder" key={folder} open>
          <summary><ChevronRight size={14} className="folder-chevron" /><FolderOpen size={16} />{folder}</summary>
          <div className="folder-cases">{folder === '完整 Agent'
            ? <button disabled={busy} aria-current={selected === 'advanced' ? 'page' : undefined} onClick={() => setSelected('advanced')}><MessageSquare size={14} />工作区实验台</button>
            : folder === 'ASI05 意外代码执行' ? executionCases.map(item => <button key={item.id} disabled={busy} aria-current={selected === `asi05:${item.id}` ? 'page' : undefined} onClick={() => setSelected(`asi05:${item.id}`)}><ShieldCheck size={14} />{item.name}</button>)
            : folder === 'ASI02 工具滥用' ? cases.map(item => <button key={item.id} disabled={busy} aria-current={selected === `asi02:${item.id}` ? 'page' : undefined} onClick={() => setSelected(`asi02:${item.id}`)}><ShieldCheck size={14} />{item.name}</button>)
            : examples.filter(item => item.folder === folder).map(item => <button key={item.id} disabled={busy} aria-current={selected === item.id ? 'page' : undefined} onClick={() => setSelected(item.id)}><MessageSquare size={14} />{item.name}</button>)}</div>
        </details>)}
      </nav>
      <p className="catalog-foot">入门案例每次独立问答。<br />文件、审批与运行观察在完整 Agent 中。</p>
      </div>
    </aside>
    {selected === 'advanced' ? <AdvancedChat onBusyChange={setBusy} /> : activeExecution ? <Asi02Chat key={`asi05:${activeExecution.id}`} category="asi05" entry={activeExecution} onBusyChange={setBusy} /> : activeCase ? <Asi02Chat key={activeCase.id} entry={activeCase} onBusyChange={setBusy} /> : example
      ? <SimpleChat key={example.id} example={example} onBusyChange={setBusy} />
      : <main className="catalog-loading" role="status">{error || '正在加载案例…'}{error && <button onClick={() => window.location.reload()}>重新加载</button>}</main>}
  </div>;
}
