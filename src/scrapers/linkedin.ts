import { existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { chromium } from "playwright";
import type { Config, JobPosting, JobScraper } from "../types.js";
import { logger } from "../logger.js";
import { withTimeout } from "../utils/withTimeout.js";

const REQUEST_DELAY_MS: [number, number] = [2000, 5000];
// Same virtualization/crash-hardening rationale as the rest of the scrapers: any single
// element read must fail fast rather than block on Playwright's default 30s timeout.
const ELEMENT_TIMEOUT_MS = 3000;
// Delay between each "load more" scroll trigger (see loadMorePosts below) — shorter than
// REQUEST_DELAY_MS since this is just waiting for the next batch of posts to render, not a
// full page navigation.
const SCROLL_DELAY_MS: [number, number] = [1200, 2200];
// Safety cap on scroll-triggered "load more" attempts per query, so a page that never
// stops growing (or never settles) can't stall a run indefinitely. Needs headroom above
// maxJobsPerRun (config.json) - each attempt only loads a handful more posts, so a low
// cap here can stop the scroll short of a high maxJobsPerRun before LinkedIn is actually
// exhausted for that query.
const MAX_LOAD_MORE_ATTEMPTS = 40;

// LinkedIn's normal Jobs search almost never includes a raw recruiter email in the JD
// (both LinkedIn and employers push applicants through the platform's own apply flow).
// Casual posts in the feed/content search, on the other hand, frequently do ("send your
// resume to x@y.com" / "reach out to careers@company.com") and LinkedIn auto-linkifies
// those as mailto: anchors — so we search LinkedIn's content vertical instead of the jobs
// vertical, using the two configured boolean queries directly as the search string.
const POST_ITEM_SELECTOR = 'div[role="listitem"]';
const EXPAND_BUTTON_SELECTOR = '[data-testid="expandable-text-button"]';

function randomDelay(): Promise<void> {
  const ms = REQUEST_DELAY_MS[0] + Math.random() * (REQUEST_DELAY_MS[1] - REQUEST_DELAY_MS[0]);
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomScrollDelay(): Promise<void> {
  const ms = SCROLL_DELAY_MS[0] + Math.random() * (SCROLL_DELAY_MS[1] - SCROLL_DELAY_MS[0]);
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// LinkedIn's content search has no "show more results" button — the initial page load
// only renders ~6 posts, and further batches only load once the last rendered post is
// scrolled into view (confirmed by testing: window/mouse-wheel scrolling does nothing,
// since the results list is its own internally-scrolled container). Repeatedly scrolling
// the last item into view triggers loading further batches, until either `targetCount`
// posts are available or the count stops growing (genuinely exhausted for this query).
async function loadMorePosts(
  items: import("playwright").Locator,
  targetCount: number
): Promise<number> {
  let previousCount = -1;
  for (let attempt = 0; attempt < MAX_LOAD_MORE_ATTEMPTS; attempt++) {
    const currentCount = await items.count();
    if (currentCount >= targetCount || currentCount === previousCount) break;
    previousCount = currentCount;
    await items
      .nth(currentCount - 1)
      .scrollIntoViewIfNeeded({ timeout: ELEMENT_TIMEOUT_MS })
      .catch(() => {});
    await randomScrollDelay();
  }
  return items.count();
}

function shortHash(text: string): string {
  return createHash("sha256").update(text).digest("hex").slice(0, 16);
}

interface ExtractedPost {
  authorName: string;
  authorUrl: string | null;
  postText: string;
  emails: string[];
}

async function extractPost(item: import("playwright").Locator): Promise<ExtractedPost | null> {
  await item.scrollIntoViewIfNeeded({ timeout: ELEMENT_TIMEOUT_MS }).catch(() => {});

  const authorLink = item.locator("a").first();
  const authorUrl = await authorLink.getAttribute("href", { timeout: ELEMENT_TIMEOUT_MS }).catch(() => null);
  const authorRaw = (await authorLink.textContent({ timeout: ELEMENT_TIMEOUT_MS }).catch(() => null)) ?? "";
  const authorName = authorRaw.split("•")[0].trim() || "Unknown";

  // Click "see more" if the post text is truncated; safe no-op if absent.
  const expandBtn = item.locator(EXPAND_BUTTON_SELECTOR).first();
  if ((await expandBtn.count()) > 0) {
    await expandBtn.click({ timeout: ELEMENT_TIMEOUT_MS }).catch(() => {});
  }

  const postText = ((await item.textContent({ timeout: ELEMENT_TIMEOUT_MS }).catch(() => null)) ?? "").trim();
  if (!postText) return null;

  const emails = await item
    .evaluate((el) =>
      Array.from(el.querySelectorAll('a[href^="mailto:" i]'))
        .map((a) => a.getAttribute("href")?.replace(/^mailto:/i, "").split("?")[0]?.trim())
        .filter((e): e is string => Boolean(e))
    )
    .catch(() => [] as string[]);

  return { authorName, authorUrl, postText, emails: [...new Set(emails)] };
}

export const linkedinScraper: JobScraper = {
  name: "linkedin",

  async scrape(cfg: Config): Promise<JobPosting[]> {
    const results: JobPosting[] = [];
    const seenHashes = new Set<string>();
    const storageStatePath = cfg.sources.linkedin.storageStatePath;

    if (!existsSync(storageStatePath)) {
      logger.warn(
        `LinkedIn storageState not found at ${storageStatePath}. Run "npm run linkedin-login" first. Skipping LinkedIn this run.`
      );
      return results;
    }

    // --no-sandbox/--disable-setuid-sandbox/--disable-dev-shm-usage are required to run
    // headless Chromium inside a proot container (Termux's proot-distro Ubuntu on Android),
    // which can't set up the user/PID namespaces Chromium's own sandbox wants.
    const browser = await chromium.launch({
      args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
    });
    try {
      const context = await browser.newContext({ storageState: storageStatePath });
      let page = await context.newPage();

      for (const query of cfg.search.queries) {
        if (results.length >= cfg.search.maxJobsPerRun) break;

        // Each configured query is a full LinkedIn content-search boolean expression
        // (e.g. `("Automation Test Engineer" OR SDET) AND ("send resume" OR ...)`), run
        // as-is - no per-keyword templating. sortBy=date_posted (rather than relevance)
        // and origin=FACETED_SEARCH match the query LinkedIn's own UI builds when sorting
        // content search by "Latest" - confirmed by hand to support the same
        // scroll-to-load-more pagination as the relevance-sorted version.
        let searchUrl = `https://www.linkedin.com/search/results/content/?keywords=${encodeURIComponent(
          query
        )}&origin=FACETED_SEARCH&sortBy=${encodeURIComponent('["date_posted"]')}`;
        if (cfg.search.regionGeoUrn) {
          searchUrl += `&geoUrn=${encodeURIComponent(`["${cfg.search.regionGeoUrn}"]`)}`;
        }

        try {
          await page.goto(searchUrl, { waitUntil: "domcontentloaded", timeout: 30000 });
          await randomDelay();

          // Diagnostic: if LinkedIn redirects to a checkpoint/verification/login page
          // instead of real search results (e.g. because the session is suddenly used
          // from an unfamiliar IP/location), the URL and title reveal it immediately,
          // rather than silently showing "0 postings" with no explanation.
          const landedUrl = page.url();
          const landedTitle = await page.title().catch(() => "(unknown)");
          logger.info(`LinkedIn: landed on "${landedTitle}" at ${landedUrl}`);

          const items = page.locator(POST_ITEM_SELECTOR);
          const targetCount = cfg.search.maxJobsPerRun - results.length;
          const rawCount = await loadMorePosts(items, targetCount);
          const count = Math.min(rawCount, targetCount);
          logger.info(`LinkedIn: found ${rawCount} raw "${POST_ITEM_SELECTOR}" element(s) for query "${query}" (after loading more posts).`);

          if (count === 0) {
            logger.warn(
              `LinkedIn: no posts found for query "${query}" (selectors may be stale, or the request was blocked).`
            );
            continue;
          }

          for (let i = 0; i < count; i++) {
            const item = items.nth(i);

            try {
              const extracted = await withTimeout(extractPost(item), 15000, "LinkedIn post extraction");
              if (!extracted) {
                logger.warn(`LinkedIn: post ${i} had no readable text, skipping.`);
                continue;
              }

              const { authorName, authorUrl, postText, emails } = extracted;
              const hash = shortHash(postText);
              // The same post can legitimately match both configured queries - dedup
              // within this run so it's only counted/emailed once.
              if (seenHashes.has(hash)) continue;
              seenHashes.add(hash);

              const descriptionText =
                emails.length > 0 ? `${postText}\n\nContact: ${emails.join(", ")}` : postText;

              const url = `${authorUrl ?? searchUrl}#post-${hash}`;

              results.push({
                id: url,
                source: "linkedin",
                url,
                // Full post text, not a short label: filters match/reject against the
                // whole body since posts have no separate title field (displayTitle
                // truncates this for display/logging purposes only).
                title: descriptionText,
                company: authorName,
                descriptionText,
                scrapedAt: new Date().toISOString(),
              });

              await randomDelay();
            } catch (err) {
              logger.warn(`LinkedIn: failed to process a post: ${(err as Error).message}`);
              if (page.isClosed() || /crashed/i.test((err as Error).message)) {
                logger.warn("LinkedIn: page appears crashed, opening a fresh page and continuing.");
                page = await context.newPage();
              }
            }
          }
        } catch (err) {
          logger.warn(`LinkedIn: content search failed for query "${query}": ${(err as Error).message}`);
          if (page.isClosed() || /crashed/i.test((err as Error).message)) {
            logger.warn("LinkedIn: page appears crashed, opening a fresh page and continuing.");
            page = await context.newPage();
          }
        }
      }
    } finally {
      await withTimeout(browser.close(), 10000, "browser.close()").catch(() => {});
    }

    return results;
  },
};
