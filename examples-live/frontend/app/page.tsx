'use client';

import { useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
import { ChevronRight, FolderOpen, MessageSquare, ShieldCheck } from 'lucide-react';
import { SimpleChat, type Example } from '@/components/simple-chat';
import { Asi02Chat, type CaseEntry } from '@/components/asi02-chat';

const AdvancedChat = dynamic(() => import('@/components/advanced-chat').then(module => module.AdvancedChat), {
  loading: () => <main className="catalog-loading">正在打开完整 Agent…</main>,
});

export default function Page() {
  const [examples, setExamples] = useState<Example[]>([]);
  const [cases, setCases] = useState<CaseEntry[]>([]);
  const [selected, setSelected] = useState('plain-chat');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.all([fetch('/api/examples'), fetch('/api/examples/asi02')])
      .then(async ([response, caseResponse]) => {
        if (!active) return;
        if (!response.ok || !caseResponse.ok) throw new Error('无法加载案例，请确认 Spring Boot 已启动。');
        const [examples, cases] = await Promise.all([response.json(), caseResponse.json()]);
        if (active) { setExamples(examples); setCases(cases); }
      })
      .catch(error => { if (active) setError(error instanceof Error ? error.message : String(error)); });
    return () => { active = false; };
  }, []);

  const activeCase = cases.find(item => `asi02:${item.id}` === selected);
  const example = examples.find(item => item.id === selected);
  return <div className="catalog-shell">
    <aside className="catalog-sidebar">
      <a className="brand" href="/"><span className="brand-icon"><ShieldCheck size={23} /></span><div>AGT<span>EXAMPLES / 从简单开始</span></div></a>
      <div className="catalog-intro">对照代码，试一次对话。</div>
      <nav aria-label="对话案例目录">
        {['原生 LangChain4j', '安全护栏入门', 'ASI02 工具滥用', '完整 Agent'].map(folder => <details className="example-folder" key={folder} open>
          <summary><ChevronRight size={14} className="folder-chevron" /><FolderOpen size={16} />{folder}</summary>
          <div className="folder-cases">{folder === '完整 Agent'
            ? <button disabled={busy} aria-current={selected === 'advanced' ? 'page' : undefined} onClick={() => setSelected('advanced')}><MessageSquare size={14} />工作区实验台</button>
            : folder === 'ASI02 工具滥用' ? cases.map(item => <button key={item.id} disabled={busy} aria-current={selected === `asi02:${item.id}` ? 'page' : undefined} onClick={() => setSelected(`asi02:${item.id}`)}><ShieldCheck size={14} />{item.name}</button>)
            : examples.filter(item => item.folder === folder).map(item => <button key={item.id} disabled={busy} aria-current={selected === item.id ? 'page' : undefined} onClick={() => setSelected(item.id)}><MessageSquare size={14} />{item.name}</button>)}</div>
        </details>)}
      </nav>
      <p className="catalog-foot">入门案例每次独立问答。<br />文件、审批与运行观察在完整 Agent 中。</p>
    </aside>
    {selected === 'advanced' ? <AdvancedChat onBusyChange={setBusy} /> : activeCase ? <Asi02Chat key={activeCase.id} entry={activeCase} onBusyChange={setBusy} /> : example
      ? <SimpleChat key={example.id} example={example} onBusyChange={setBusy} />
      : <main className="catalog-loading" role="status">{error || '正在加载案例…'}{error && <button onClick={() => window.location.reload()}>重新加载</button>}</main>}
  </div>;
}
