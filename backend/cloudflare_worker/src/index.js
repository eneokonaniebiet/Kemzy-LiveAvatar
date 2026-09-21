const RENDER_GATEWAY = "https://kemzy-liveavatar-api.onrender.com";

function proxyRequest(request) {
  const incoming = new URL(request.url);
  const target = new URL(RENDER_GATEWAY);
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

      if (url.pathname === "/ready") {
        const response = await fetch(RENDER_GATEWAY + "/ready", {
          headers: { "accept": "application/json" },
          cf: { cacheTtl: 0, cacheEverything: false },
        });
        const data = await response.json();

        return Response.json({
          status: response.ok ? (data.status || "unknown") : "degraded",
          gateway: "workers.dev",
          renderer: data.renderer || "kaggle-gpu-worker",
          backend: data.backend || "personalive",
          upstream: "hidden",
          workers_connected: data.workers_connected ?? 0,
          error: data.error || null,
        }, { status: response.ok ? 200 : 503 });
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
