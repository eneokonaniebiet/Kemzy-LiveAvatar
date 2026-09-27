const UPSTREAM_URL = "https://kemzy-liveavatar-api.onrender.com";

function proxyRequest(request) {
  const incoming = new URL(request.url);
  const target = new URL(UPSTREAM_URL);
  target.pathname = incoming.pathname;
  target.search = incoming.search;

  const headers = new Headers(request.headers);
  headers.delete("host");
  headers.delete("content-length");

  return fetch(new Request(target.toString(), {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual",
  }));
}

export default {
  async fetch(request) {
    try {
      const url = new URL(request.url);

      if (url.pathname === "/health") {
        return Response.json({
          status: "ok",
          service: "kemzy-cloudflare-gateway",
          gateway: "workers.dev",
        });
      }

      return await proxyRequest(request);
    } catch (error) {
      return Response.json({
        status: "degraded",
        service: "kemzy-cloudflare-gateway",
        error: error instanceof Error ? error.message : String(error),
      }, { status: 503 });
    }
  },
};
