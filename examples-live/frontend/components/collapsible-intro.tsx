'use client';

import { useId, useState, type ReactNode } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';

/** 收起说明时保留标题和展开入口，正文与源码保持挂载，避免丢失当前阅读状态。 */
export function CollapsibleIntro({ eyebrow, title, badge, children }: { eyebrow: ReactNode; title: string; badge?: ReactNode; children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);
  const contentId = useId();
  return <section className={`case-overview${collapsed ? ' is-collapsed' : ''}`} aria-label="案例说明">
    <header className="header">
      <div className="case-title"><span className="eyebrow">{eyebrow}</span><h1>{title}</h1></div>
      <div className="case-heading-actions"><div className="case-badge">{badge}</div><button className="overview-toggle" type="button" aria-expanded={!collapsed} aria-controls={contentId} onClick={() => setCollapsed(value => !value)}>
        {collapsed ? <ChevronDown size={15} /> : <ChevronUp size={15} />}<span>{collapsed ? '展开说明' : '收起说明'}</span>
      </button></div>
    </header>
    <div id={contentId} className="case-overview-body" hidden={collapsed}>{children}</div>
  </section>;
}
