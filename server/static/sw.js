// Makes Android install this as an app. Suggestions are never stored.
self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.map((key) => caches.delete(key)));
      await self.clients.claim();
    })(),
  );
});

function renderWakePage(text) {
  return (
    text.includes("SERVICE WAKING UP") ||
    text.includes("APPLICATION LOADING") ||
    text.includes("WELCOME TO RENDER") ||
    text.includes("START BUILDING ON RENDER TODAY")
  );
}

async function boxDocument(request) {
  let last = null;
  for (let attempt = 0; attempt < 40; attempt++) {
    const response = await fetch(request, { cache: "no-store" });
    const text = await response.clone().text();
    if (!renderWakePage(text)) return response;
    last = response;
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  return last || fetch(request, { cache: "no-store" });
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (
    url.pathname.startsWith("/media") ||
    url.pathname.startsWith("/app/") ||
    url.pathname === "/health"
  ) {
    return;
  }
  if (request.mode === "navigate" || url.pathname === "/") {
    event.respondWith(boxDocument(request));
    return;
  }
  event.respondWith(fetch(request, { cache: "no-store" }));
});
