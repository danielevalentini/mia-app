package it.test;


import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public class SiteWordFinder {

    private final String seedUrl;
    private final String domainHost;
    private final String targetWord = "You’ve probably already heard of Rome as a romantic place to experience as a couple";
    private final int maxPages;
    private final boolean useSeleniumForRendering;
    private final String seleniumGridUrl; // es. http://localhost:4444/wd/hub

    public SiteWordFinder(String seedUrl, int maxPages,
                          boolean useSeleniumForRendering, String seleniumGridUrl) throws Exception {
        this.seedUrl = normalizeUrl(seedUrl);
        this.domainHost = new URL(this.seedUrl).getHost();
        this.maxPages = maxPages;
        this.useSeleniumForRendering = useSeleniumForRendering;
        this.seleniumGridUrl = seleniumGridUrl;
    }

    private String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    public static class Result {
        public final String url;
        public final String snippet;
        public final String screenshotPath; // null se non fatto

        public Result(String url, String snippet, String screenshotPath) {
            this.url = url;
            this.snippet = snippet;
            this.screenshotPath = screenshotPath;
        }
    }

    public List<Result> find() throws Exception {
        List<Result> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(seedUrl);
        visited.add(seedUrl);

        WebDriver seleniumDriver = null;
        if (useSeleniumForRendering) {
            seleniumDriver = createRemoteWebDriver();
        }

        while (!queue.isEmpty() && visited.size() <= maxPages) {
            String current = queue.remove();
            System.out.println("[Crawl] " + current);
            String html;
            try {
                if (useSeleniumForRendering && seleniumDriver != null) {
                    seleniumDriver.get(current);
                    Thread.sleep(8000); // piccolo wait per JS (aggiustabile)

                    handleCookiePopup(seleniumDriver);

                    html = seleniumDriver.getPageSource();
                } else {
                    Document doc = Jsoup.connect(current)
                            .userAgent("Mozilla/5.0 (compatible; SiteWordFinder/1.0)")
                            .timeout(10_000)
                            .get();
                    html = doc.html();
                }
            } catch (Exception e) {
                System.err.println("Errore fetching " + current + " : " + e.getMessage());
                continue;
            }

            String lower = html.toLowerCase();
            if (lower.contains(targetWord.toLowerCase())) {
                String snippet = extractSnippet(html, targetWord, 150);
                String screenshotPath = null;
                if (useSeleniumForRendering && seleniumDriver != null) {
                    screenshotPath = takeScreenshot(seleniumDriver, current);
                }
                found.add(new Result(current, snippet, screenshotPath));
                System.out.println(" --> Trovata parola in: " + current);
            }

            // Parse links and add same-domain ones
            try {
                Document doc = Jsoup.parse(html, current);
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String href = link.absUrl("href");
                    if (href == null || href.isEmpty()) continue;
                    try {
                        URL u = new URL(href);
                        if (!u.getHost().equalsIgnoreCase(domainHost)) continue;
                        // skip non-http(s)
                        if (!u.getProtocol().startsWith("http")) continue;
                        // normalize remove fragments
                        URI uri = new URI(u.getProtocol(), u.getUserInfo(), u.getHost(), u.getPort(),
                                u.getPath(), u.getQuery(), null);
                        String normalized = uri.toString();
                        if (!visited.contains(normalized) && visited.size() < maxPages) {
                            visited.add(normalized);
                            queue.add(normalized);
                        }
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                // ignore parsing errors
            }
        }

        if (seleniumDriver != null) {
            seleniumDriver.quit();
        }

        return found;
    }

    private String extractSnippet(String html, String word, int contextChars) {
        String text = Jsoup.parse(html).text();
        String lower = text.toLowerCase();
        String w = word.toLowerCase();
        int idx = lower.indexOf(w);
        if (idx == -1) return "";
        int start = Math.max(0, idx - contextChars/2);
        int end = Math.min(text.length(), idx + w.length() + contextChars/2);
        return text.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private WebDriver createRemoteWebDriver() throws MalformedURLException {
        URL grid = new URL(seleniumGridUrl);
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setBrowserName("chrome"); // puoi cambiare o parametrizzare
        // Timeout / implicit waits non impostati qui; gestire se serve
        return new RemoteWebDriver(grid, caps);
    }

    private String takeScreenshot(WebDriver driver, String pageUrl) {
        try {
            if (!(driver instanceof TakesScreenshot)) return null;
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            String safeName = pageUrl.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
            String filename = "screenshot_" + safeName + ".png";
            File out = new File(filename);
            Files.copy(src.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return out.getAbsolutePath();
        } catch (IOException e) {
            System.err.println("Errore screenshot: " + e.getMessage());
            return null;
        }
    }

    // --- main utilità per eseguire dalla riga di comando ---
    public static void main(String[] args) throws Exception {

        String url = "https://www.trivago.it/";
        int maxPages = 5;
        boolean useSelenium = true;// false;
        String grid = "http://localhost:4444/wd/hub";

        SiteWordFinder finder = new SiteWordFinder(url, maxPages, useSelenium, grid);
        List<Result> results = finder.find();

        System.out.println("\n=== Trovati " + results.size() + " risultati ===");
        for (Result r : results) {
            System.out.println("URL: " + r.url);
            System.out.println("Snippet: " + r.snippet);
            System.out.println("Screenshot: " + (r.screenshotPath == null ? "-" : r.screenshotPath));
            System.out.println("---------------------------");
        }
    }

    private void handleCookiePopup(WebDriver driver) {
        try {
            // 1️⃣ Selettori mirati a pulsanti di consenso (basati su attributi e testo)
            List<By> selectors = Arrays.asList(
                    // basati su attributi "data-action" o "data-action-type"
                    By.cssSelector("button[data-action='consent'][data-action-type='accept']"),
                    By.cssSelector("button[data-action-type='accept']"),
                    By.cssSelector("button[data-action='accept']"),

                    // basati su id o classi comuni
                    By.cssSelector("button#accept"),
                    By.cssSelector("button.accept"),
                    By.cssSelector("button.uc-accept-button"),

                    By.id("accept"),
                    // fallback per testo interno
                    By.xpath("//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), ' Allow all ')]"),
                    By.xpath("//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'accetta')]"),
                    By.xpath("//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'accept')]")
            );

            boolean clicked = false;

            for (By selector : selectors) {
                List<WebElement> elements = driver.findElements(selector);
                if (!elements.isEmpty()) {
                    for (WebElement el : elements) {
                        if (el.isDisplayed() && el.isEnabled()) {
                            el.click();
                            System.out.println("✅ Cookie banner chiuso con selettore: " + selector);
                            Thread.sleep(500);
                            clicked = true;
                            break;
                        }
                    }
                }
                if (clicked) break;
            }

            // 2️⃣ fallback — se non clicca nulla, rimuove gli overlay "cookie"
            if (!clicked) {
                ((JavascriptExecutor) driver).executeScript(
                        "let els = document.querySelectorAll('*');" +
                                "for (let e of els) {" +
                                " if (e.innerText && e.innerText.toLowerCase().includes('cookie')) e.remove();" +
                                "}"
                );
                System.out.println("ℹ️ Nessun bottone cookie trovato, popup rimosso via JS");
            }

        } catch (Exception e) {
            System.out.println("⚠️ Errore gestione cookie banner: " + e.getMessage());
        }
    }
}