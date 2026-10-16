import { NextRequest } from 'next/server';
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (!['agent', 'examples'].includes(path[0]) || path.some(part => part === '..' || !/^[a-zA-Z0-9-]+$/.test(part))) {
    return Response.json({ message: 'Unknown API' }, { status: 404 });
  }
  const headers = new Headers();
  for (const name of ['content-type', 'cookie', 'origin']) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  try {
    const upstream = await fetch(`${process.env.AGT_API_URL || 'http://127.0.0.1:8080'}/api/${path.join('/')}`, {
      method: request.method, headers, cache: 'no-store', signal: request.signal,
      body: ['GET', 'HEAD'].includes(request.method) ? undefined : await request.text(),
    });
    const responseHeaders = new Headers({ 'Cache-Control': 'no-cache, no-transform', 'X-Accel-Buffering': 'no' });
    for (const name of ['content-type', 'set-cookie']) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders.set(name, value);
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    return Response.json({ message: '无法连接 Agent 服务，请确认 Spring Boot 已启动。' }, { status: 502 });
  }
}
export const GET = proxy;
export const POST = proxy;
export const DELETE = proxy;
