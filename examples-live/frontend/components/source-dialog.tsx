'use client';

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Code2, X } from 'lucide-react';

/** 原生 modal 提供焦点约束、Esc 关闭和关闭后的焦点恢复。 */
export function SourceDialog({ open, onClose, title, children, closeLabel = '关闭源码弹窗' }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode; closeLabel?: string;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const element = dialog.current;
    if (!open || !element) return;
    element.showModal();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      element.close();
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  return <dialog ref={dialog} className="source-dialog" aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); onClose(); }}
    onClick={event => {
      if (event.target !== event.currentTarget) return;
      const bounds = event.currentTarget.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose();
    }}>
    <header className="source-dialog-heading">
      <div><Code2 size={19} /><h2 id={titleId}>{title}</h2></div>
      <button type="button" aria-label={closeLabel} onClick={onClose} autoFocus><X size={20} /><span>关闭</span></button>
    </header>
    <div className="source-panel source-dialog-content">{open ? children : null}</div>
  </dialog>;
}
