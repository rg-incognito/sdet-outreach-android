import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { chromium, type Browser } from "playwright";
import { marked } from "marked";
import { withTimeout } from "../utils/withTimeout.js";

// Read as a static asset from src/ (not dist/) since tsc doesn't copy .html
// files on build; both `tsx src/index.ts` and `node dist/index.js` are run
// with the project root as cwd, so this path resolves in both dev and prod.
const TEMPLATE_PATH = join("src", "resume", "resume.template.html");

async function renderWithBrowser(browser: Browser, html: string, outputPath: string): Promise<Buffer> {
  const page = await browser.newPage();
  try {
    await withTimeout(page.setContent(html, { waitUntil: "domcontentloaded" }), 15000, "page.setContent()");
    const pdfBuffer = await withTimeout(
      page.pdf({
        format: "Letter",
        printBackground: true,
        margin: { top: "0", bottom: "0", left: "0", right: "0" },
      }),
      15000,
      "page.pdf()"
    );
    await writeFile(outputPath, pdfBuffer);
    return pdfBuffer;
  } finally {
    await page.close().catch(() => {});
  }
}

export async function renderResumeToPdf(
  markdown: string,
  outputPath: string,
  browser?: Browser
): Promise<Buffer> {
  const template = await readFile(TEMPLATE_PATH, "utf-8");
  const contentHtml = await marked.parse(markdown);
  const html = template.replace("{{CONTENT}}", contentHtml);

  if (browser) {
    try {
      return await renderWithBrowser(browser, html, outputPath);
    } catch {
      // The shared browser instance may have crashed from unrelated prior use (seen in
      // practice under sustained scraping load); fall back to a fresh throwaway browser
      // rather than letting one bad render take down every remaining job in the run.
      const fallbackBrowser = await chromium.launch({
        args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
      });
      try {
        return await renderWithBrowser(fallbackBrowser, html, outputPath);
      } finally {
        await withTimeout(fallbackBrowser.close(), 10000, "fallbackBrowser.close()").catch(() => {});
      }
    }
  }

  const ownBrowser = await chromium.launch({
    args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
  });
  try {
    return await renderWithBrowser(ownBrowser, html, outputPath);
  } finally {
    await withTimeout(ownBrowser.close(), 10000, "ownBrowser.close()").catch(() => {});
  }
}
