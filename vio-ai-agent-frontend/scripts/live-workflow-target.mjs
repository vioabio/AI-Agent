// Explicit isolated loopback targets only. The daily 3000/8123 services are never a default.
export function liveWorkflowTargets(env = process.env) {
  const parse = (value, pathname) => {
    let url
    try { url = new URL(value) } catch { throw new Error('Explicit isolated workflow URLs are required') }
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || url.pathname !== pathname
      || Number(url.port) < 1024 || ['3000', '8123'].includes(url.port)) throw new Error('Use dedicated loopback HTTP ports and fixed paths')
    return url
  }
  const frontend = parse(env.N5_E2E_BASE_URL, '/'), api = parse(env.VIO_WORKFLOW_LIVE_API, '/api')
  if (frontend.port === api.port) throw new Error('Browser and backend must use different ports')
  return { frontendOrigin: frontend.origin, frontendHost: frontend.hostname, frontendPort: frontend.port,
    apiOrigin: api.origin, apiUrl: api.href }
}
