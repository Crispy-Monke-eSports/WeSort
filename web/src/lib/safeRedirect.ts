/** Only known pages are valid post-authentication destinations. */
const PAGES = new Set([
  "/overview",
  "/inventory",
  "/orders",
  "/bots",
  "/operations",
  "/settings",
]);

/** Reject absolute, scheme-relative, malformed and unknown destinations. */
export function safeLoginRedirect(input: string | undefined): string {
  if (!input || !input.startsWith("/") || input.startsWith("//"))
    return "/overview";
  try {
    const url = new URL(input, "https://wesort.invalid");
    if (url.origin !== "https://wesort.invalid" || !PAGES.has(url.pathname))
      return "/overview";
    return url.pathname + url.search + url.hash;
  } catch {
    return "/overview";
  }
}
