import { test } from 'node:test';
import assert from 'node:assert/strict';
import { traceStartError } from './trace-errors.ts';

test('separates origin, capacity, model and connection failures by real HTTP/code', () => {
  assert.match(traceStartError(403), /来源.*HTTP 403/);
  assert.match(traceStartError(429, 'trace_demo_busy'), /正在运行/);
  assert.match(traceStartError(503, 'trace_store_full'), /100 条/);
  assert.match(traceStartError(503, 'trace_model_unavailable'), /初始化模型/);
  assert.match(traceStartError(502), /连接后端/);
  assert.match(traceStartError(504, 'trace_start_timeout'), /可能仍在运行/);
  assert.doesNotMatch(traceStartError(503, 'untrusted-secret'), /untrusted-secret|模型配置/);
});
