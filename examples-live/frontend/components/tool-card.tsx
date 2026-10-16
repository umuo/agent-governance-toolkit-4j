import { Check, ChevronDown, LoaderCircle, X } from 'lucide-react';
export type Tool = { key: string; id: string; name: string; arguments: string; result?: string; stdout?: string; stderr?: string; exitCode?: number; recoverable?: boolean; duration?: number; state: 'receiving' | 'running' | 'done' | 'error' };
export function ToolCard({ tool }: { tool: Tool }) {
  const working = tool.state === 'receiving' || tool.state === 'running';
  return <details className={`tool-card ${tool.state}`} open={working || tool.state === 'error' || undefined}>
    <summary>{working ? <LoaderCircle size={15} className="spin" /> : tool.state === 'error' ? <X size={15} /> : <Check size={15} />}<span className="tool-name">{tool.name || '工具参数接收中'}</span><span className="tool-state">{({ receiving: '参数流 · 未检查', running: '执行中', done: '已完成', error: tool.recoverable ? '工具失败 · 已返回 Agent' : '失败' })[tool.state]}{tool.duration !== undefined && ` · ${tool.duration} ms`}</span><ChevronDown size={14} /></summary>
    <div className="tool-detail"><span className="micro">ARGUMENTS</span><pre>{tool.arguments || '等待参数…'}</pre>{tool.stderr !== undefined ? <>
      {tool.stdout && <><span className="micro">STDOUT</span><pre>{tool.stdout}</pre></>}
      <span className="micro">STDERR</span><pre>{tool.stderr || tool.result || '未捕获到 stderr'}</pre>
      {tool.exitCode !== undefined && <span className="micro">EXIT CODE · {tool.exitCode} · 输出已检查</span>}
    </> : tool.result !== undefined && <><span className="micro">RESULT</span><pre>{tool.result}</pre></>}</div>
  </details>;
}
