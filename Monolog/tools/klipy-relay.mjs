const route = /^\/(gifs|stickers)\/(trending|search)$/;

function reply(status, error, upstreamStatus) {
  return Response.json({ error, upstreamStatus }, {
    status,
    headers: { "Cache-Control": "no-store" }
  });
}

export default {
  async fetch(request, { KLIPY_APP_KEY }) {
    if (request.method !== "GET") return reply(405, "Use GET.");
    const incoming = new URL(request.url);
    const match = route.exec(incoming.pathname);
    if (!match) return reply(404, "Use /gifs/trending, /stickers/trending, or a search route.");
    const key = KLIPY_APP_KEY?.trim();
    if (!key) return reply(503, "Set the KLIPY_APP_KEY secret in Worker settings.");

    const [, catalog, action] = match;
    const query = incoming.searchParams.get("q")?.trim() ?? "";
    if (action === "search" && (!query || query.length > 80)) {
      return reply(400, "Search terms must be 1 to 80 characters.");
    }
    const requestedLocale = incoming.searchParams.get("locale") ?? "";
    const locale = /^[a-z]{2}$/.test(requestedLocale) ? requestedLocale : "us";
    const upstream = new URL(
      `https://api.klipy.com/api/v1/${encodeURIComponent(key)}/${catalog}/${action}`
    );
    upstream.searchParams.set("page", "1");
    upstream.searchParams.set("per_page", "16");
    upstream.searchParams.set("content_filter", "high");
    upstream.searchParams.set("locale", locale);
    if (action === "search") upstream.searchParams.set("q", query);

    try {
      const result = await fetch(upstream, {
        headers: { Accept: "application/json" },
        redirect: "manual",
        signal: AbortSignal.timeout(8000)
      });
      if (!result.ok) {
        const limited = result.status === 429;
        const message = limited ? "KLIPY rate limit reached." : "KLIPY did not return a successful response.";
        return reply(limited ? 429 : 502, message, result.status);
      }
      return new Response(result.body, {
        headers: { "Cache-Control": "no-store", "Content-Type": "application/json" }
      });
    } catch {
      return reply(502, "The Worker could not connect to KLIPY.");
    }
  }
};
