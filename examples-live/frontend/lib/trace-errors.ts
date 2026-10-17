const messages: Record<string, string> = {
  trace_demo_busy: '已有链路演示正在运行，请刷新历史查看进度。',
  origin_denied: '后端仍在执行来源限制，请重启已更新的后端。',
  trace_model_unavailable: '后端无法初始化模型，请检查模型配置文件与服务端环境。',
  trace_store_full: '本地链路存储已达到 100 条上限，请管理历史存储后重试。',
  trace_store_unavailable: '本地链路存储不可写或已失效，请检查目录权限与磁盘状态。',
  trace_start_timeout: '启动确认超时，任务可能仍在运行，请先刷新历史。',
  trace_start_failed: '后端未能创建执行链路，请检查服务端运行状态。',
};

export function traceStartError(status: number, code?: string): string {
  const known = code && Object.hasOwn(messages, code) ? code : undefined;
  const fallback = status === 403 ? messages.origin_denied
    : status === 429 ? messages.trace_demo_busy
      : status === 404 ? '链路启动接口不存在，请确认前后端版本一致。'
        : status === 502 ? '前端无法连接后端，请确认后端服务与 API 地址。'
          : '链路启动失败，请检查后端服务状态。';
  return `${known ? messages[known] : fallback}（HTTP ${status}${known ? ` · ${known}` : ''}）`;
}
