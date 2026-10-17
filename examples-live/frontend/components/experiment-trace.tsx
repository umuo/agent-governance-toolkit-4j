'use client';

import { useState } from 'react';
import type { TraceStep } from '@/lib/asi02';

const labels: Record<string, string> = {
  experiment_request: '本轮实验输入', model_input: '模型输入（LangChain4j）',
  http_request: '实际 HTTP 请求体', http_response: '实际 HTTP 响应体',
  http_error: 'HTTP 请求失败', model_response: '模型返回（LangChain4j）', model_error: '模型请求失败',
  tool_start: '工具执行开始', tool_result: '工具执行结果', tool_error: '工具执行异常',
  business_check: '宿主边界检查', governance: '治理检查', run_end: '本轮结束',
};

function stepHint(step: TraceStep): string {
  const body = step.body as Record<string, unknown> | null;
  if (!body || typeof body !== 'object') return '';
  if (step.kind === 'tool_start') return String(body.name ?? '');
  if (step.kind === 'governance') {
    const audit = body.audit as Record<string, unknown> | undefined;
    return [audit?.point, audit?.verdict, audit?.reason, audit?.approval].filter(Boolean).join(' · ');
  }
  if (step.kind === 'run_end') return [body.status, body.code].filter(Boolean).join(' · ');
  return '';
}

export function ExperimentTrace({ steps }: { steps: TraceStep[] }) {
  const [mode, setMode] = useState<'default' | 'all' | 'none'>('default');
  if (!steps.length) return <p className="evidence-note">这条历史结果没有过程记录，请重新运行。</p>;
  return <details className="experiment-trace">
    <summary>完整调用过程 · {steps.length} 步 · 含完整请求体</summary>
    <p className="trace-explanation">按实际发生顺序记录。HTTP 请求体包含发给模型的完整消息、工具定义与参数；模型原始响应为检查前的诊断数据。请求头与密钥不展示。框架输入单独标注，不作为 HTTP 请求体。</p>
    <div className="trace-actions"><button type="button" onClick={() => setMode('all')}>展开全部内容</button><button type="button" onClick={() => setMode('none')}>收起全部内容</button></div>
    <ol className="trace-steps">{steps.map(step => <li key={step.sequence}>
      <details key={`${step.sequence}-${mode}`} open={mode === 'all' || (mode === 'default' && step.kind === 'http_request')}>
        <summary><span className="trace-step-title">{step.sequence}. {labels[step.kind] ?? step.kind}</span><span className="trace-step-time">{step.round > 0 ? `第 ${step.round} 轮 · ` : ''}+{step.elapsedMillis} ms</span>{stepHint(step) && <small>{stepHint(step)}</small>}</summary>
        <pre tabIndex={0} aria-label={`${labels[step.kind] ?? step.kind}完整内容`}><code>{JSON.stringify(step.body, null, 2)}</code></pre>
      </details>
    </li>)}</ol>
  </details>;
}
