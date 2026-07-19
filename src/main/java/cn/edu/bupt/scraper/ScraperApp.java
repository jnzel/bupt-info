package cn.edu.bupt.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class ScraperApp {

    public static void main(String[] args) {
        // Read credentials from Environment Variables
        String username = System.getenv("BUPT_USERNAME");
        String password = System.getenv("BUPT_PASSWORD");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Error: Environment variables BUPT_USERNAME and BUPT_PASSWORD must be set.");
            System.exit(1);
        }

        System.out.println("Starting BUPT RSS Scraper...");
        
        // Use try-with-resources to ensure Playwright closes correctly
        try (Playwright playwright = Playwright.create()) {
            System.out.println("Launching Chromium in headless mode...");
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox")));
            
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));
            
            Page page = context.newPage();

            try {
                // 1. Navigate to the landing auth gateway page
                String entryUrl = "https://mymob.bupt.edu.cn";
                System.out.println("Navigating to: " + entryUrl);
                page.navigate(entryUrl);
                page.waitForLoadState();

                // 2. Perform Login on CAS page (handles redirect if needed)
                String currentUrl = page.url();
                System.out.println("Current URL: " + currentUrl);

                if (currentUrl.contains("authserver") || page.locator("input").count() > 0 || page.locator("#loginIframe").count() > 0) {
                    System.out.println("Authentication page detected. Proceeding to log in...");
                    
                    // Determine if the login form is loaded inside an iframe (like BUPT unified authentication page)
                    boolean hasIframe = page.locator("#loginIframe").count() > 0;
                    
                    if (hasIframe) {
                        System.out.println("Login iframe (#loginIframe) detected. Switching context to iframe...");
                        FrameLocator iframe = page.frameLocator("#loginIframe");
                        
                        // Wait for the "密码登录" (Password Login) tab inside the iframe and click it
                        Locator passwordTab = iframe.locator("a[i18n='login.type.password']");
                        try {
                            System.out.println("Waiting for password login tab in iframe...");
                            passwordTab.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                            
                            // Wait for network to be idle to ensure JS handlers are registered
                            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                            page.waitForTimeout(1000);
                            
                            System.out.println("Clicking password login tab inside iframe via JS evaluation...");
                            passwordTab.evaluate("el => el.click()"); // Forces programmatic trigger
                            page.waitForTimeout(1500); // Wait for transition animation
                        } catch (Exception e) {
                            System.out.println("Password login tab inside iframe not clicked: " + e.getMessage());
                        }
                        
                        // Locate inputs and button inside the iframe
                        Locator usernameInput = iframe.locator("input#username:visible, input[name='username']:visible, input[type='text']:visible").first();
                        Locator passwordInput = iframe.locator("input#password:visible, input[name='password']:visible, input[type='password']:visible").first();
                        Locator submitButton = iframe.locator("button[type='submit']:visible, input[type='submit']:visible, .auth_login_btn:visible, #login-submit:visible, button:has-text('登录'):visible, input:has-text('登录'):visible").first();
                        
                        // Fill credentials and submit
                        usernameInput.fill(username);
                        passwordInput.fill(password);
                        System.out.println("Credentials filled inside iframe. Submitting form...");
                        submitButton.click();
                        
                    } else {
                        // Standard main page login (direct)
                        // Wait for the "密码登录" (Password Login) tab and click it
                        Locator passwordTab = page.locator("a[i18n='login.type.password']");
                        try {
                            passwordTab.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                            page.waitForTimeout(1000);
                            System.out.println("Clicking password login tab via JS evaluation...");
                            passwordTab.evaluate("el => el.click()");
                            page.waitForTimeout(1500); // Wait for transition animation
                        } catch (Exception e) {
                            System.out.println("Password login tab not clicked: " + e.getMessage());
                        }
                        
                        // Locate inputs and button
                        Locator usernameInput = page.locator("input#username:visible, input[name='username']:visible, input[type='text']:visible").first();
                        Locator passwordInput = page.locator("input#password:visible, input[name='password']:visible, input[type='password']:visible").first();
                        Locator submitButton = page.locator("button[type='submit']:visible, input[type='submit']:visible, .auth_login_btn:visible, #login-submit:visible, button:has-text('登录'):visible, input:has-text('登录'):visible").first();
                        
                        // Fill credentials and submit
                        usernameInput.fill(username);
                        passwordInput.fill(password);
                        System.out.println("Credentials filled. Submitting form...");
                        submitButton.click();
                    }
                    
                    // Wait for the page redirection and load
                    page.waitForLoadState();
                    System.out.println("Redirected URL: " + page.url());
                } else {
                    System.out.println("Already logged in or bypassed authentication page directly.");
                }

                // Calculate today and yesterday date formats for filtering
                java.time.LocalDate localDate = java.time.LocalDate.now();
                java.time.format.DateTimeFormatter ymd = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
                java.time.format.DateTimeFormatter md = java.time.format.DateTimeFormatter.ofPattern("MM-dd");
                
                String d1_ymd = localDate.format(ymd);
                String d1_md = localDate.format(md);
                String d2_ymd = localDate.minusDays(1).format(ymd);
                String d2_md = localDate.minusDays(1).format(md);
                System.out.println("Date filter window: Today=" + d1_ymd + "/" + d1_md + ", Yesterday=" + d2_ymd + "/" + d2_md);

                // 3. Navigate to the secondary list page
                String listUrl = "https://mymob.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154";
                System.out.println("Navigating to secondary list page: " + listUrl);
                page.navigate(listUrl);
                page.waitForLoadState();
                page.waitForTimeout(2000); // Wait for page to initialize

                // Click "查看更多" to load older updates dynamically (up to 8 times max)
                int maxClicks = 8;
                for (int c = 0; c < maxClicks; c++) {
                    Locator currentLinks = page.locator("a[href*='info'], a[href*='content'], a[href*='detail']");
                    int linkCount = currentLinks.count();
                    if (linkCount > 0) {
                        // Check the parent text of the last (oldest) visible item on the page
                        Locator lastLink = currentLinks.nth(linkCount - 1);
                        String parentText = (String) lastLink.evaluate("el => el.parentElement ? el.parentElement.innerText : ''");
                        
                        // If the oldest item loaded is already older than 2 days, stop clicking "查看更多"
                        if (isOlderThanTwoDays(parentText, d1_ymd, d1_md, d2_ymd, d2_md)) {
                            System.out.println("Oldest visible item on page is older than 2 days. Stopping '查看更多' clicks.");
                            break;
                        }
                    }
                    
                    Locator moreButton = page.locator("text='查看更多'");
                    if (moreButton.count() > 0 && moreButton.first().isVisible()) {
                        System.out.println("Clicking '查看更多' (" + (c + 1) + ")...");
                        try {
                            moreButton.first().click();
                            page.waitForTimeout(2000); // Wait for new items to render
                        } catch (Exception e) {
                            System.out.println("Clicking '查看更多' failed: " + e.getMessage());
                            break;
                        }
                    } else {
                        System.out.println("No visible '查看更多' button found.");
                        break;
                    }
                }

                System.out.println("Scraping information list from secondary page...");
                List<RssItem> rssItems = new ArrayList<>();
                Set<String> processedUrls = new HashSet<>();
                
                // Find all anchor links that represent notifications/articles
                Locator links = page.locator("a[href*='info'], a[href*='content'], a[href*='detail'], .news-list a, .list a");
                int count = links.count();
                System.out.println("Found " + count + " potential article/notification links.");

                // Dump a screenshot to help debug if it returns nothing
                if (count == 0) {
                    System.out.println("No elements found matching generic selectors. Saving screenshot & HTML dump for troubleshooting...");
                    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("debug_login_result.png")));
                    try (FileWriter fw = new FileWriter("debug_page.html")) {
                        fw.write(page.content());
                    }
                    System.out.println("Saved 'debug_login_result.png' and 'debug_page.html' to project root.");
                }

                int collectLimit = 25; // Max limit to collect
                for (int i = 0; i < count; i++) {
                    Locator linkElement = links.nth(i);
                    String title = linkElement.innerText().trim();
                    String url = linkElement.getAttribute("href");

                    if (url == null || url.isEmpty() || title.isEmpty() || title.length() < 4) {
                        continue; // Skip noise (e.g. empty links, footer links)
                    }

                    // Resolve relative URLs to absolute
                    if (!url.startsWith("http")) {
                        if (url.startsWith("/")) {
                            url = "https://mymob.bupt.edu.cn" + url;
                        } else {
                            url = page.url().substring(0, page.url().lastIndexOf('/') + 1) + url;
                        }
                    }

                    if (processedUrls.contains(url)) {
                        continue;
                    }

                    // Check date in the parent element containing this link
                    String parentText = (String) linkElement.evaluate("el => el.parentElement ? el.parentElement.innerText : ''");
                    boolean withinTwoDays = parentText.contains(d1_ymd) || parentText.contains(d1_md) ||
                                            parentText.contains(d2_ymd) || parentText.contains(d2_md);
                    
                    // Stop collecting if we reached older items AND we have at least 5 safety items
                    if (!withinTwoDays && rssItems.size() >= 5) {
                        System.out.println("Reached items older than 2 days and already collected " + rssItems.size() + " safety items. Stopping list collection.");
                        break;
                    }
                    
                    // Stop completely if we hit hard collection limit
                    if (rssItems.size() >= collectLimit) {
                        break;
                    }

                    processedUrls.add(url);
                    String pubDate = getFormattedCurrentDate();
                    RssItem item = new RssItem(title, url, "Loading...", pubDate);
                    rssItems.add(item);
                    System.out.println("Collected [" + (withinTwoDays ? "2-Days" : "Older") + "]: " + title + " -> " + url);
                }

                // 3.2. Second Pass: Navigate to each detail page and scrape the full content (for top 10 items)
                int fetchLimit = 10;
                System.out.println("Total collected: " + rssItems.size() + " items. Fetching full contents for the top " + Math.min(rssItems.size(), fetchLimit) + " items...");
                
                List<RssItem> finalItems = new ArrayList<>();
                for (int i = 0; i < Math.min(rssItems.size(), fetchLimit); i++) {
                    RssItem item = rssItems.get(i);
                    try {
                        System.out.println("Fetching details (" + (i + 1) + "/" + Math.min(rssItems.size(), fetchLimit) + "): " + item.title);
                        page.navigate(item.url);
                        page.waitForLoadState();
                        page.waitForTimeout(1000); // Give it a second to load dynamic DOM
                        
                        // Selectors for BUPT detail page content (typical university CMS templates)
                        Locator contentLocator = page.locator(".v_news_content, #content, .content, .article, .show-content, [name='_newscontent_fromname'], .con-detail");
                        String detailContent = "";
                        
                        if (contentLocator.count() > 0 && contentLocator.first().isVisible()) {
                            detailContent = contentLocator.first().innerHTML().trim();
                        } else {
                            // Fallback: grab text from body
                            String text = page.locator("body").innerText().trim();
                            if (text.length() > 600) {
                                text = text.substring(0, 600) + "...";
                            }
                            detailContent = text.replace("\n", "<br>");
                        }
                        
                        item.description = detailContent;
                        finalItems.add(item);
                    } catch (Exception e) {
                        System.err.println("Failed to fetch content for " + item.url + ": " + e.getMessage());
                        item.description = "无法获取详细内容，可能页面需要额外认证或结构已变动。";
                        finalItems.add(item);
                    }
                }
                
                // Add the remaining collected items without full-text (as fallback / title-only)
                for (int i = fetchLimit; i < rssItems.size(); i++) {
                    RssItem item = rssItems.get(i);
                    item.description = "点击链接查看详情（需登录学校门户）";
                    finalItems.add(item);
                }

                // 4. Generate RSS 2.0 XML using the fully populated items
                generateRssXml(finalItems);

                System.out.println("Scraper finished successfully!");

            } catch (Exception innerEx) {
                System.err.println("Exception caught during browser execution. Saving screenshot and page HTML...");
                try {
                    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("debug_error.png")));
                    try (FileWriter fw = new FileWriter("debug_error.html")) {
                        fw.write(page.content());
                    }
                    System.out.println("Saved 'debug_error.png' and 'debug_error.html' for analysis.");
                } catch (Exception ex) {
                    System.err.println("Failed to write debug info: " + ex.getMessage());
                }
                throw innerEx; // rethrow to keep build status correct
            }

        } catch (Exception e) {
            System.err.println("Scraper execution encountered an error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean isOlderThanTwoDays(String text, String d1_ymd, String d1_md, String d2_ymd, String d2_md) {
        // Match standard date formats like yyyy-MM-dd or MM-dd
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            String foundDate = m.group();
            // If the date found in text is neither today nor yesterday, it is older than 2 days
            return !foundDate.contains(d1_ymd) && !foundDate.contains(d1_md) &&
                   !foundDate.contains(d2_ymd) && !foundDate.contains(d2_md);
        }
        return false;
    }

    private static String getFormattedCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return sdf.format(new Date());
    }

    private static void generateRssXml(List<RssItem> items) throws IOException {
        String filename = "rss.xml";
        System.out.println("Generating " + filename + " with " + items.size() + " items...");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
        xml.append("<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n");
        xml.append("<channel>\n");
        xml.append("  <title>北邮移动门户订阅源</title>\n");
        xml.append("  <link>https://mymob.bupt.edu.cn</link>\n");
        xml.append("  <description>北京邮电大学移动端门户自动抓取订阅源</description>\n");
        xml.append("  <language>zh-cn</language>\n");
        xml.append("  <lastBuildDate>").append(getFormattedCurrentDate()).append("</lastBuildDate>\n");
        xml.append("  <atom:link href=\"https://raw.githubusercontent.com/BUPT-RSS/bupt-info/main/rss.xml\" rel=\"self\" type=\"application/rss+xml\" />\n");

        for (RssItem item : items) {
            xml.append("  <item>\n");
            xml.append("    <title><![CDATA[").append(item.title).append("]]></title>\n");
            xml.append("    <link>").append(item.url.replace("&", "&amp;")).append("</link>\n");
            xml.append("    <guid>").append(item.url.replace("&", "&amp;")).append("</guid>\n");
            xml.append("    <description><![CDATA[").append(item.description).append("]]></description>\n");
            xml.append("    <pubDate>").append(item.pubDate).append("</pubDate>\n");
            xml.append("  </item>\n");
        }

        xml.append("</channel>\n");
        xml.append("</rss>\n");

        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(xml.toString());
        }
        System.out.println("Successfully generated " + filename);
    }

    private static class RssItem {
        String title;
        String url;
        String description;
        String pubDate;

        RssItem(String title, String url, String description, String pubDate) {
            this.title = title;
            this.url = url;
            this.description = description;
            this.pubDate = pubDate;
        }
    }
}
