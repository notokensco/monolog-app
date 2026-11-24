const headers = { "Cache-Control": "no-store" };

function reply(status, body) {
  return Response.json(body, { status, headers });
}

async function readInput(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new Error("Missing body");
  const decoder = new TextDecoder();
  let json = "";
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) return JSON.parse(json + decoder.decode());
      size += value.byteLength;
      if (size > 8192) {
        await reader.cancel();
        throw new Error("Body too large");
      }
      json += decoder.decode(value, { stream: true });
    }
  } finally {
    reader.releaseLock();
  }
}

function httpsUrl(value, allowedHost) {
  if (typeof value !== "string" || value.length > 4096) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password &&
      (!allowedHost || url.host === allowedHost) ? url.href : null;
  } catch {
    return null;
  }
}

export default {
  async fetch(request, { BRAVE_SEARCH_API_KEY, SEARCH_RATE_LIMITER }) {
    if (new URL(request.url).pathname !== "/search") return reply(404, { error: "Use /search." });
    if (request.method !== "POST") return reply(405, { error: "Use POST." });
    const key = BRAVE_SEARCH_API_KEY?.trim();
    if (!key) return reply(503, { error: "Search is unavailable." });

    let input;
    try { input = await readInput(request); }
    catch { return reply(400, { error: "Invalid search request." }); }
    const query = typeof input?.query === "string" ? input.query.trim() : "";
    const { kind, safeSearch } = input ?? {};
    if (!query || query.length > 400 || query.split(/\s+/u).length > 50 ||
        !["web", "images"].includes(kind) || typeof safeSearch !== "boolean") {
      return reply(400, { error: "Invalid search request." });
    }

    if (SEARCH_RATE_LIMITER) {
      const id = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const { success } = await SEARCH_RATE_LIMITER.limit({ key: id });
      if (!success) return reply(429, { error: "Search rate limit reached." });
    }

    const images = kind === "images";
    const endpoint = new URL("https://api.search.brave.com/res/v1/" + kind + "/search");
    endpoint.searchParams.set("q", query);
    endpoint.searchParams.set("count", images ? "8" : "5");
    endpoint.searchParams.set("safesearch", safeSearch ? "strict" : "off");
    if (!images) {
      endpoint.searchParams.set("result_filter", "web");
      endpoint.searchParams.set("text_decorations", "false");
    }

    try {
      const upstream = await fetch(endpoint, {
        headers: { Accept: "application/json", "X-Subscription-Token": key },
        redirect: "manual",
        signal: AbortSignal.timeout(7000)
      });
      if (!upstream.ok) return reply(upstream.status === 429 ? 429 : 502, { error: "Search is unavailable." });
      const data = await upstream.json();
      if (typeof data.query?.safesearch === "boolean" && data.query.safesearch !== safeSearch) {
        return reply(502, { error: "Search filtering could not be confirmed." });
      }
      const entries = images ? data.results : data.web?.results;
      const results = (Array.isArray(entries) ? entries : []).slice(0, 12).map(item => ({
        url: httpsUrl(item.url),
        title: typeof item.title === "string" ? item.title.slice(0, 140) : "",
        content: typeof item.description === "string" ? item.description.slice(0, 350) : "",
        thumbnail: httpsUrl(item.thumbnail?.src, "imgs.search.brave.com")
      })).filter(item => item.url && (!images || item.thumbnail));
      return reply(200, { engine: "brave", kind, safeSearch, results });
    } catch {
      return reply(502, { error: "Search is unavailable." });
    }
  }
};
