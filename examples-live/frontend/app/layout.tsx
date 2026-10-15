import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = { title: 'AGT · Agent Lab', description: 'Agent 对话与运行时治理实验台' };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body>{children}</body></html>;
}
