import { chromium } from "playwright";
import type { Config, FilterCounts, JobPosting } from "./types.js";
import { logger } from "./logger.js";
import { readPosts } from "./sheets/postsSheet.js";
import { getSentEmails } from "./sheets/trackerSheet.js";
import { loadMasterResume } from "./resume/loadMasterResume.js";
import { createGeminiClient } from "./gemini/client.js";
import { tailorAndSend } from "./emailJob.js";
import { withTimeout } from "./utils/withTimeout.js";
import { displayTitle } from "./utils/displayTitle.js";

// The send step: does zero scraping. Reads raw postings from the Posts sheet (populated
// separately by scripts/scrape-to-sheet.ts), dedups against every recruiter email already
// logged in the Tracker sheet - across all time - then tailors and sends for the rest.
export async function runPipeline(cfg: Config, dryRun: boolean): Promise<FilterCounts> {
  logger.info(`Starting run${dryRun ? " --dry-run" : ""}...`);

  const posts = await readPosts(cfg);
  const sentEmails = await getSentEmails(cfg);

  const withEmail = posts.filter((p) => p.recruiterEmail);
  const pending = withEmail.filter((p) => !sentEmails.has(p.recruiterEmail.trim().toLowerCase()));

  const counts: FilterCounts = {
    scraped: posts.length,
    hasEmail: withEmail.length,
    sent: 0,
  };

  logger.info(
    `Posts sheet: ${posts.length} total, ${withEmail.length} with an email, ${pending.length} not yet sent to.`
  );

  if (pending.length === 0) {
    logger.info("Nothing new to send this run.");
    return counts;
  }

  const capped = pending.slice(0, cfg.search.maxJobsPerRun);
  if (capped.length < pending.length) {
    logger.info(`Capping this run to ${capped.length} of ${pending.length} pending row(s) (maxJobsPerRun).`);
  }

  const masterResume = await loadMasterResume(cfg.masterResumePath);
  const geminiClient = createGeminiClient(cfg);
  const browser = await chromium.launch({
    args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
  });

  // Guards against sending twice in the same run if the Posts sheet has more than one
  // row for the same recruiter email (e.g. they posted more than once).
  const sentThisRun = new Set<string>();

  try {
    for (const post of capped) {
      const email = post.recruiterEmail.trim().toLowerCase();
      if (sentThisRun.has(email)) continue;

      const job: JobPosting = {
        id: post.jobUrl,
        source: "linkedin",
        url: post.jobUrl,
        title: post.title,
        company: post.company,
        descriptionText: post.description,
        scrapedAt: post.scrapedAt,
      };

      try {
        const result = await tailorAndSend(cfg, geminiClient, masterResume, browser, job, post.recruiterEmail, dryRun);
        if (result.sent) {
          counts.sent += 1;
          sentThisRun.add(email);
        }
      } catch (err) {
        logger.error(`Failed to process ${displayTitle(job.title)} @ ${job.company}: ${(err as Error).message}`);
      }
    }
  } finally {
    await withTimeout(browser.close(), 10000, "browser.close()").catch(() => {});
  }

  logger.info(`Run complete. Sent ${counts.sent} email(s) this run.`);
  return counts;
}
