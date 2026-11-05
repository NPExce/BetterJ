// Counts how many installs of BetterJ are in use: POST /ping stores one row per install and day,
// GET /stats returns the counts only. No IP address, user name or project is stored.

const INSTALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const VERSION = /^[\w.+-]{1,40}$/;

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname === "/ping") return ping(request, env);
    if (request.method === "GET" && pathname === "/stats") return stats(env);
    return new Response("Not found", { status: 404 });
  },
};

async function ping(request, env) {
  const body = await request.json().catch(() => null);
  const { id, version, ide } = body ?? {};
  if (!INSTALL_ID.test(id ?? "") || !VERSION.test(version ?? "") || !VERSION.test(ide ?? "")) {
    return new Response("Bad request", { status: 400 });
  }
  const day = new Date().toISOString().slice(0, 10);
  await env.DB.prepare("INSERT OR IGNORE INTO pings (day, id, version, ide) VALUES (?1, ?2, ?3, ?4)")
    .bind(day, id, version, ide)
    .run();
  return new Response(null, { status: 204 });
}

async function stats(env) {
  const users = (days) =>
    env.DB.prepare("SELECT COUNT(DISTINCT id) AS n FROM pings WHERE day > date('now', ?1)").bind(`-${days} days`).first("n");
  const [today, last7Days, last30Days] = await Promise.all([users(1), users(7), users(30)]);
  const daily = await env.DB.prepare(
    "SELECT day, COUNT(*) AS users FROM pings WHERE day > date('now', '-30 days') GROUP BY day ORDER BY day",
  ).all();
  const versions = await env.DB.prepare(
    "SELECT version, COUNT(DISTINCT id) AS users FROM pings WHERE day > date('now', '-7 days') GROUP BY version ORDER BY users DESC",
  ).all();
  return Response.json({ today, last7Days, last30Days, daily: daily.results, versions: versions.results });
}
