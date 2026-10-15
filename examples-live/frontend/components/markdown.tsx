import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
export function Markdown({ text }: { text: string }) {
  return <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} components={{ a: ({ children, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer">{children}</a>, table: ({ children }) => <div className="table-scroll"><table>{children}</table></div> }}>{text}</ReactMarkdown></div>;
}
