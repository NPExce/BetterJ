// Counts how many installs of each of Adrian's tools are in use (BetterJ, BetterGitHub, ...): POST /ping stores one
// row per product, install and day; GET /stats returns the counts only. No IP address, user name or project is stored.

const INSTALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PRODUCTS = ["betterj", "bettergithub"];
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
  const { product = "betterj", id, version, ide = "-" } = body ?? {};
  if (!PRODUCTS.includes(product) || !INSTALL_ID.test(id ?? "") || !VERSION.test(version ?? "") || !VERSION.test(ide)) {
    return new Response("Bad request", { status: 400 });
  }
  const day = new Date().toISOString().slice(0, 10);
  await env.DB.prepare("INSERT OR IGNORE INTO pings (day, product, id, version, ide) VALUES (?1, ?2, ?3, ?4, ?5)")
    .bind(day, product, id, version, ide)
    .run();
  return new Response(null, { status: 204 });
}

async function stats(env) {
  const rows = (sql, ...params) => env.DB.prepare(sql).bind(...params).all().then((r) => r.results);
  const users = (days) =>
    rows("SELECT product, COUNT(DISTINCT id) AS n FROM pings WHERE day > date('now', ?1) GROUP BY product", `-${days} days`);
  const [today, week, month, daily, versions] = await Promise.all([
    users(1),
    users(7),
    users(30),
    rows("SELECT product, day, COUNT(*) AS users FROM pings WHERE day > date('now', '-30 days') GROUP BY product, day ORDER BY day"),
    rows("SELECT product, version, COUNT(DISTINCT id) AS users FROM pings WHERE day > date('now', '-7 days') GROUP BY product, version ORDER BY users DESC"),
  ]);
  const products = {};
  const of = (product) => (products[product] ??= { today: 0, last7Days: 0, last30Days: 0, daily: [], versions: [] });
  for (const r of today) of(r.product).today = r.n;
  for (const r of week) of(r.product).last7Days = r.n;
  for (const r of month) of(r.product).last30Days = r.n;
  for (const r of daily) of(r.product).daily.push({ day: r.day, users: r.users });
  for (const r of versions) of(r.product).versions.push({ version: r.version, users: r.users });
  return Response.json(products);
}
