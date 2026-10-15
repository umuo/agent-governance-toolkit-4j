import { Check, ShieldCheck, X } from 'lucide-react';
import { Markdown } from './markdown';
import { ToolCard, type Tool } from './tool-card';
import { modelContent, type Activity } from '../lib/process';
export type Approval = { id: string; tool: string; arguments: unknown; expiresAt: number; decision?: string };
export function ExecutionProcess({ activity, tools, approvals, now, running, checkedText, finalCall, onDecide }: {
  activity: Activity[]; tools: Tool[]; approvals: Approval[]; now: number; running: boolean;
  checkedText: string; finalCall?: number; onDecide: (approval: Approval, approved: boolean) => void;
}) {
  const finalChecked = checkedText.length > 0 && modelContent(activity, finalCall) === checkedText;
  return <div className="execution-process">{activity.map(item => {
    if (item.kind === 'tool') {
      const tool = tools.find(tool => tool.key === item.key);
      return tool ? <ToolCard key={`tool:${item.key}`} tool={tool} /> : null;
    }
    if (item.kind === 'approval') {
      const approval = approvals.find(approval => approval.id === item.key);
      return approval ? <div className="approval" key={`approval:${item.key}`}><div className="approval-title"><ShieldCheck size={18} /><strong>需要你的批准</strong><span>{approval.decision || (approval.expiresAt <= now ? '已过期' : `${Math.max(0, Math.ceil((approval.expiresAt - now) / 1000))}s`)}</span></div><p>Agent 请求执行 <code>{approval.tool}</code>。请核对完整参数。</p><pre>{JSON.stringify(approval.arguments, null, 2)}</pre>{!approval.decision && running && approval.expiresAt > now && <div className="approval-actions"><button onClick={() => onDecide(approval, false)}><X size={15} /> 拒绝</button><button className="approve" onClick={() => onDecide(approval, true)}><Check size={15} /> 批准此动作</button></div>}</div> : null;
    }
    if (!('text' in item)) return null;
    if (item.kind === 'reasoning') return <details className="reasoning-card" open key={item.key}><summary><span>Reasoning</span><span>ROUND {item.call} · 接口返回 · 未检查</span></summary><Markdown text={item.text} /></details>;
    const checked = finalChecked && item.call === finalCall;
    return <div className={`process-content ${checked ? 'checked' : ''}`} key={item.key}><span className="process-label">RESPONSE CONTENT · ROUND {item.call}<span>{checked ? '最终答复 · 已检查' : '过程输出 · 未检查'}</span></span><Markdown text={item.text} /></div>;
  })}</div>;
}
