export type Activity =
  | { kind: 'content' | 'reasoning'; key: string; call: number; text: string }
  | { kind: 'tool' | 'approval'; key: string };

/** Append only consecutive fragments, preserving model / tool / approval arrival order. */
export function appendModelDelta(activity: Activity[], kind: 'content' | 'reasoning', call: number, text: string): Activity[] {
  const last = activity.at(-1);
  if (last && last.kind === kind && 'call' in last && last.call === call) {
    return [...activity.slice(0, -1), { ...last, text: last.text + text }];
  }
  return [...activity, { kind, key: `${kind}:${call}:${activity.length}`, call, text }];
}
export function appendReference(activity: Activity[], kind: 'tool' | 'approval', key: string): Activity[] {
  return activity.some(item => item.kind === kind && item.key === key) ? activity : [...activity, { kind, key }];
}
export function modelContent(activity: Activity[], call?: number): string {
  return activity.filter(item => item.kind === 'content' && item.call === call).map(item => 'text' in item ? item.text : '').join('');
}
