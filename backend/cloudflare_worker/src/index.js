const DISCOVERY_URL = "https://kemzy-liveavatar-api.onrender.com/ready";

async function readJson(response, label) {
  const text = await response.text();
  if (!text) throw new Error(label + " returned an empty response");
  try {
    return JSON.parse(text);
  } catch {
    const compact = text.replace(/\s+/g, " ").trim().slice(0, 180);
    throw new Error(label + " returned non-JSON HTTP " + response.status + ": " + compact);
  }
}

async function discoverUpstream() {
  const response = await fetch(DISCOVERY_URL, {
    headers: { "accept": "application/json" },
    cf: { cacheTtl: 0, cacheEverything: false },
  });
  if (!response.ok) throw new Error("GPU discovery failed: HTTP " + response.status);
  const data = await readJson(response, "Render discovery");
  const upstream = String(data.renderer || "").trim().replace(/\/$/, "");
  if (!upstream.startsWith("https://")) throw new Error("GPU renderer URL is unavailable");
  return upstream;
}

function proxyRequest(request, upstream) {
  const incoming = new URL(request.url);
  const target = new URL(upstream);
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
        return Response.json({ status: "ok", service: "kemzy-cloudflare-gateway", gateway: "workers.dev" });
      }
      if (url.pathname === "/ready") {
        const upstream = await discoverUpstream();
        const gpuResponse = await fetch(upstream + "/ready", {
          headers: { "accept": "application/json" },
          cf: { cacheTtl: 0, cacheEverything: false },
        });
        const data = await readJson(gpuResponse, "GPU renderer");
        return Response.json({
          status: gpuResponse.ok ? (data.status || "unknown") : "degraded",
          gateway: "workers.dev",
          renderer: "cloud-gpu",
          backend: data.backend || "unknown",
          upstream: "hidden",
          error: data.error || null,
        }, { status: gpuResponse.ok ? 200 : 503 });
      }
      const upstream = await discoverUpstream();
      return await proxyRequest(request, upstream);
    } catch (error) {
      return Response.json({
        status: "degraded",
        service: "kemzy-cloudflare-gateway",
        error: error instanceof Error ? error.message : String(error),
      }, { status: 503 });
    }
  },
};
