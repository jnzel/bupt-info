package cn.edu.bupt.scraper;

import com.microsoft.playwright.*;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class ScraperApp {

    public static void main(String[] args) {
        // 从环境变量读取凭据
        String username = System.getenv("BUPT_USERNAME");
        String password = System.getenv("BUPT_PASSWORD");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Error: Environment variables BUPT_USERNAME and BUPT_PASSWORD must be set.");
            System.exit(1);
        }

        System.out.println("Starting BUPT RSS Scraper...");

        // 使用 try-with-resources 确保 Playwright 正确关闭
        try (Playwright playwright = Playwright.create()) {
            System.out.println("Launching Chromium in headless mode...");
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            try {
                // 1. 导航到登录认证网关页面
                String entryUrl = "https://mymob.bupt.edu.cn";
                System.out.println("Navigating to: " + entryUrl);
                page.navigate(entryUrl);
                page.waitForLoadState();

                // 2. 在 CAS 页面进行登录（处理必要的重定向）
                String currentUrl = page.url();
                System.out.println("Current URL: " + currentUrl);

                if (currentUrl.contains("authserver") || page.locator("input").count() > 0
                        || page.locator("#loginIframe").count() > 0) {
                    System.out.println("Authentication page detected. Proceeding to log in...");

                    boolean hasIframe = page.locator("#loginIframe").count() > 0;

                    if (hasIframe) {
                        System.out.println("Login iframe (#loginIframe) detected. Switching context to iframe...");
                        FrameLocator iframe = page.frameLocator("#loginIframe");
                        performLogin(iframe, page, username, password);
                    } else {
                        System.out.println("Direct login page detected (no iframe).");
                        performLogin(page, username, password);
                    }

                    // 等待页面重定向和加载
                    page.waitForLoadState();
                    System.out.println("Redirected URL: " + page.url());
                } else {
                    System.out.println("Already logged in or bypassed authentication page directly.");
                }

                // 计算今天和昨天的日期格式用于过滤
                java.time.LocalDate localDate = java.time.LocalDate.now();
                java.time.format.DateTimeFormatter ymd = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
                java.time.format.DateTimeFormatter md = java.time.format.DateTimeFormatter.ofPattern("MM-dd");

                String d1_ymd = localDate.format(ymd);
                String d1_md = localDate.format(md);
                String d2_ymd = localDate.minusDays(1).format(ymd);
                String d2_md = localDate.minusDays(1).format(md);
                System.out.println(
                        "Date filter window: Today=" + d1_ymd + "/" + d1_md + ", Yesterday=" + d2_ymd + "/" + d2_md);

                // 3. 导航到二级列表页面
                String listUrl = "https://mymob.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154";
                System.out.println("Navigating to secondary list page: " + listUrl);
                page.navigate(listUrl);
                page.waitForLoadState();
                page.waitForTimeout(2000); // 等待页面初始化

                // 点击“查看更多”动态加载更旧的更新（最多 8 次）
                int maxClicks = 8;
                for (int c = 0; c < maxClicks; c++) {
                    Locator currentLinks = page.locator("a[href*='info'], a[href*='content'], a[href*='detail']");
                    int linkCount = currentLinks.count();
                    if (linkCount > 0) {
                        // 检查页面上最后一个（最旧的）可见条目的父元素文本
                        Locator lastLink = currentLinks.nth(linkCount - 1);
                        String parentText = (String) lastLink
                                .evaluate("el => el.parentElement ? el.parentElement.innerText : ''");

                        // 如果加载的最旧条目已经超过 2 天，停止点击“查看更多”
                        if (isOlderThanTwoDays(parentText, d1_ymd, d1_md, d2_ymd, d2_md)) {
                            System.out.println(
                                    "Oldest visible item on page is older than 2 days. Stopping clicks.");
                            break;
                        }
                    }

                    Locator moreButton = page.locator("text='查看更多'");
                    if (moreButton.count() > 0 && moreButton.first().isVisible()) {
                        System.out.println("Clicking '查看更多' (" + (c + 1) + ")...");
                        try {
                            moreButton.first().click();
                            page.waitForTimeout(2000); // 等待新条目渲染
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

                // 查找所有表示通知/文章的锚点链接
                Locator links = page
                        .locator("a[href*='info'], a[href*='content'], a[href*='detail'], .news-list a, .list a");
                int count = links.count();
                System.out.println("Found " + count + " potential article/notification links.");

                // 如果没有返回任何内容，保存屏幕截图以帮助调试
                if (count == 0) {
                    System.out.println(
                            "No elements found matching generic selectors. Saving screenshot & HTML dump for troubleshooting...");
                    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("debug_login_result.png")));
                    try (FileWriter fw = new FileWriter("debug_page.html")) {
                        fw.write(page.content());
                    }
                    System.out.println("Saved 'debug_login_result.png' and 'debug_page.html' to project root.");
                }

                int collectLimit = 25; // 最大收集限制
                for (int i = 0; i < count; i++) {
                    Locator linkElement = links.nth(i);
                    String title = linkElement.innerText().trim();
                    String url = linkElement.getAttribute("href");

                    if (url == null || url.isEmpty() || title.isEmpty() || title.length() < 4) {
                        continue; // 跳过噪音（例如空链接、页脚链接）
                    }

                    // 将相对 URL 解析为绝对 URL
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

                    // 检查包含该链接的父元素中的日期
                    String parentText = (String) linkElement
                            .evaluate("el => el.parentElement ? el.parentElement.innerText : ''");
                    boolean withinTwoDays = parentText.contains(d1_ymd) || parentText.contains(d1_md) ||
                            parentText.contains(d2_ymd) || parentText.contains(d2_md);

                    // 如果已收集到超过 2 天的条目，且已收集了至少 5 个安全条目，则停止列表收集
                    if (!withinTwoDays && rssItems.size() >= 5) {
                        System.out.println("Reached items older than 2 days and already collected " + rssItems.size()
                                + " safety items. Stopping list collection.");
                        break;
                    }

                    // 如果达到硬收集限制，则完全停止
                    if (rssItems.size() >= collectLimit) {
                        break;
                    }

                    processedUrls.add(url);
                    String pubDate = getFormattedCurrentDate();
                    RssItem item = new RssItem(title, url, "Loading...", pubDate);
                    rssItems.add(item);
                    System.out.println(
                            "Collected [" + (withinTwoDays ? "2-Days" : "Older") + "]: " + title + " -> " + url);
                }

                // 3.2. 第二阶段：导航到每个详情页面并抓取完整内容（针对前 10 个条目）
                int fetchLimit = 10;
                System.out.println("Total collected: " + rssItems.size() + " items. Fetching full contents for the top "
                        + Math.min(rssItems.size(), fetchLimit) + " items...");

                List<RssItem> finalItems = new ArrayList<>();
                for (int i = 0; i < Math.min(rssItems.size(), fetchLimit); i++) {
                    RssItem item = rssItems.get(i);
                    try {
                        System.out.println("Fetching details (" + (i + 1) + "/" + Math.min(rssItems.size(), fetchLimit)
                                + "): " + item.title);
                        page.navigate(item.url);
                        page.waitForLoadState();
                        page.waitForTimeout(2000);

                        System.out.println("  Detail page URL: " + page.url());

                        // 检查是否被重定向到登录页面
                        if (page.url().contains("authserver") || page.url().contains("login")) {
                            System.out.println("  WARNING: Detail page redirected to login! Session may have expired.");
                            item.description = "<p>文章内容需要登录后查看。</p><p><a href=\"" + item.url + "\">点击访问原文</a></p>";
                            finalItems.add(item);
                            continue;
                        }

                        // 尝试多个选择器来获取 BUPT 详情页内容
                        String[] selectors = {
                                ".v_news_content",
                                "#content",
                                ".content",
                                ".article",
                                ".show-content",
                                "[name='_newscontent_fromname']",
                                ".con-detail",
                                ".wp_articlecontent",
                                ".article-content",
                                "#vsb_content",
                                ".entry-content",
                                ".news-content",
                                ".detail-content",
                                "article"
                        };

                        String detailContent = "";
                        for (String selector : selectors) {
                            Locator loc = page.locator(selector);
                            if (loc.count() > 0 && loc.first().isVisible()) {
                                detailContent = loc.first().innerHTML().trim();
                                System.out.println("  Matched selector: " + selector + " (content length: "
                                        + detailContent.length() + ")");
                                break;
                            }
                        }

                        if (detailContent.isEmpty()) {
                            // 备用方案：抓取整个页面的 HTML，以便 RSS 阅读器可以渲染它
                            System.out.println(
                                    "  WARNING: No content selector matched. Using full body innerHTML as fallback.");
                            // 记录第一个选择器的调试信息
                            System.out.println("  Page title: " + page.title());

                            String bodyHtml = page.locator("body").innerHTML().trim();
                            if (bodyHtml.length() > 10000) {
                                bodyHtml = bodyHtml.substring(0, 10000) + "...";
                            }
                            detailContent = bodyHtml;
                        }

                        item.description = detailContent;
                        finalItems.add(item);
                    } catch (Exception e) {
                        System.err.println("Failed to fetch content for " + item.url + ": " + e.getMessage());
                        item.description = "<p>无法获取详细内容，可能页面需要额外认证或结构已变动。</p><p><a href=\"" + item.url
                                + "\">点击访问原文</a></p>";
                        finalItems.add(item);
                    }
                }

                // 添加剩余未获取全文的已收集条目（作为备用/仅标题）
                for (int i = fetchLimit; i < rssItems.size(); i++) {
                    RssItem item = rssItems.get(i);
                    item.description = "点击链接查看详情（需登录学校门户）";
                    finalItems.add(item);
                }

                // 4. 将每篇文章保存为独立的 HTML 文件，以便通过 GitHub Pages 访问
                String pagesBaseUrl = "https://jnzel.github.io/bupt-info/articles/";
                java.io.File articlesDir = new java.io.File("articles");
                if (!articlesDir.exists()) {
                    articlesDir.mkdirs();
                }

                for (RssItem item : finalItems) {
                    String articleId = extractArticleId(item.url);
                    String articleFilename = articleId + ".html";
                    String articleHtml = generateArticleHtml(item.title, item.description, item.pubDate);

                    try (FileWriter fw = new FileWriter(new java.io.File(articlesDir, articleFilename))) {
                        fw.write(articleHtml);
                    }

                    // 更新条目 URL 指向 GitHub Pages 的文章
                    item.pagesUrl = pagesBaseUrl + articleFilename;
                    System.out.println("Saved article: " + articleFilename);
                }

                // 5. 使用完全填充的条目生成 RSS 2.0 XML
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

    /**
     * 当表单在 iframe 内部时，在 CAS 页面执行登录。
     */
    private static void performLogin(FrameLocator iframe, Page page, String username, String password) {
        Locator passwordTab = iframe.locator("a[i18n='login.type.password']");
        Locator usernameInput = iframe.locator("input#username, input[name='username']").first();
        Locator passwordInput = iframe.locator("input#password, input[name='password']").first();
        Locator submitButton = iframe.locator("text=账号登录").first();

        switchToPasswordTab(passwordTab, usernameInput, page);
        fillAndSubmit(usernameInput, passwordInput, submitButton, username, password);
    }

    /**
     * 当表单直接在页面上（无 iframe）时，在 CAS 页面执行登录。
     */
    private static void performLogin(Page page, String username, String password) {
        Locator passwordTab = page.locator("a[i18n='login.type.password']");
        Locator usernameInput = page.locator("input#username, input[name='username']").first();
        Locator passwordInput = page.locator("input#password, input[name='password']").first();
        Locator submitButton = page.locator("text=账号登录").first();

        switchToPasswordTab(passwordTab, usernameInput, page);
        fillAndSubmit(usernameInput, passwordInput, submitButton, username, password);
    }

    private static void switchToPasswordTab(Locator passwordTab, Locator usernameInput, Page page) {
        try {
            System.out.println("Waiting for password login tab to appear...");
            passwordTab.waitFor(new Locator.WaitForOptions().setTimeout(10000));
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);

            System.out.println("Clicking password login tab (Playwright native click)...");
            passwordTab.click();

            System.out.println("Waiting for username input to become visible...");
            usernameInput.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            System.out.println("Success: Password login inputs are now visible!");
        } catch (Exception e) {
            System.err.println("Failed to switch to password login tab: " + e.getMessage());
            try {
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("debug_tab_switch.png")));
                System.out.println("Saved 'debug_tab_switch.png' for diagnosis.");
            } catch (Exception ignored) {
            }
        }
    }

    private static void fillAndSubmit(Locator usernameInput, Locator passwordInput, Locator submitButton,
            String username, String password) {
        usernameInput.fill(username);
        passwordInput.fill(password);
        System.out.println("Credentials filled. Submitting login form...");
        submitButton.click();
    }

    /**
     * 从 URL 中提取唯一的文章 ID 以用作文件名。
     */
    private static String extractArticleId(String url) {
        // 尝试提取 wbnewsid 参数
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("wbnewsid=(\\d+)").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        // 备用方案：对 URL 进行哈希处理
        return String.valueOf(Math.abs(url.hashCode()));
    }

    /**
     * 为单篇文章生成包含干净样式的独立 HTML 页面。
     */
    private static String generateArticleHtml(String title, String content, String pubDate) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>").append(escapeHtml(title)).append("</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; ");
        html.append(
                "max-width: 800px; margin: 0 auto; padding: 20px; line-height: 1.8; color: #333; background: #fafafa; }\n");
        html.append(
                "h1 { font-size: 1.5em; color: #1a1a1a; border-bottom: 2px solid #0066cc; padding-bottom: 10px; }\n");
        html.append(".meta { color: #888; font-size: 0.9em; margin-bottom: 20px; }\n");
        html.append(
                ".content { background: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }\n");
        html.append(".content img { max-width: 100%; height: auto; }\n");
        html.append(".content table { border-collapse: collapse; width: 100%; }\n");
        html.append(".content td, .content th { border: 1px solid #ddd; padding: 8px; }\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<h1>").append(escapeHtml(title)).append("</h1>\n");
        html.append("<div class=\"meta\">").append(escapeHtml(pubDate)).append("</div>\n");
        html.append("<div class=\"content\">").append(content).append("</div>\n");
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static boolean isOlderThanTwoDays(String text, String d1_ymd, String d1_md, String d2_ymd, String d2_md) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            String foundDate = m.group();
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
        xml.append(
                "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">\n");
        xml.append("<channel>\n");
        xml.append("  <title>信息门户</title>\n");
        xml.append("  <link>https://jnzel.github.io/bupt-info/</link>\n");
        xml.append("  <description>BUPT信息门户RSS</description>\n");
        xml.append("  <language>zh-cn</language>\n");
        xml.append("  <lastBuildDate>").append(getFormattedCurrentDate()).append("</lastBuildDate>\n");
        xml.append(
                "  <atom:link href=\"https://raw.githubusercontent.com/jnzel/bupt-info/main/rss.xml\" rel=\"self\" type=\"application/rss+xml\" />\n");

        for (RssItem item : items) {
            // 生成纯文本摘要（先剥离 <style> 块，然后剥离标签）
            String textSummary = item.description.replaceAll("(?s)<style[^>]*>.*?</style>", "");
            textSummary = textSummary.replaceAll("<[^>]+>", "").trim();
            textSummary = textSummary.replaceAll("\\s+", " ");
            if (textSummary.length() > 300) {
                textSummary = textSummary.substring(0, 300) + "...";
            }

            // 使用 GitHub Pages URL 作为链接（不需要登录）
            String linkUrl = (item.pagesUrl != null) ? item.pagesUrl : item.url;

            xml.append("  <item>\n");
            xml.append("    <title><![CDATA[").append(item.title).append("]]></title>\n");
            xml.append("    <link>").append(linkUrl.replace("&", "&amp;")).append("</link>\n");
            xml.append("    <guid>").append(item.url.replace("&", "&amp;")).append("</guid>\n");
            xml.append("    <description><![CDATA[").append(textSummary).append("]]></description>\n");
            xml.append("    <content:encoded><![CDATA[").append(item.description).append("]]></content:encoded>\n");
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
        String pagesUrl; // 独立文章页面的 GitHub Pages URL

        RssItem(String title, String url, String description, String pubDate) {
            this.title = title;
            this.url = url;
            this.description = description;
            this.pubDate = pubDate;
        }
    }
}
