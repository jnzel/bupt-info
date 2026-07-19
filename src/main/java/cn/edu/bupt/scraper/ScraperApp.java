package cn.edu.bupt.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ScraperApp {

    // ---- 配置常量 ----
    // feed 保留窗口：只在 rss.xml 中保留发布日期在最近 RETENTION_DAYS 个自然日内的条目
    private static final int RETENTION_DAYS = 7;
    // 安全上限：防止通知高峰期 feed 无限膨胀（article 存档不受此限制）
    private static final int MAX_FEED_ITEMS = 60;

    private static final String PORTAL_ORIGIN = "https://mymob.bupt.edu.cn";
    private static final String ENTRY_URL = PORTAL_ORIGIN;
    private static final String LIST_URL = PORTAL_ORIGIN + "/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154";
    private static final String PAGES_BASE_URL = "https://jnzel.github.io/bupt-info/articles/";
    private static final String RSS_FILENAME = "rss.xml";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // GMT+8 时区，用于把“自然日”落到确定的时刻，保证 pubDate 逐次运行稳定不变
    private static final ZoneId ZONE_CN = ZoneId.of("GMT+8");
    // RFC-822 pubDate 格式（RSS 规范要求的时间写法）
    private static final String RFC822_PATTERN = "EEE, dd MMM yyyy HH:mm:ss z";
    // 匹配 yyyy-MM-dd（优先）或 MM-dd 两种列表页常见日期写法
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})|(\\d{2})-(\\d{2})");

    public static void main(String[] args) {
        // 从环境变量读取凭据
        String username = System.getenv("BUPT_USERNAME");
        String password = System.getenv("BUPT_PASSWORD");

        System.out.println("Starting");

        // 使用 try-with-resources 确保 Playwright 正确关闭
        try (Playwright playwright = Playwright.create()) {
            System.out.println("Launching Chromium in headless mode");
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT));

            Page page = context.newPage();

            try {
                // 1. 导航到登录认证网关页面
                System.out.println("Navigating to: " + ENTRY_URL);
                page.navigate(ENTRY_URL);
                page.waitForLoadState();

                // 2. 在 CAS 页面进行登录（处理必要的重定向）
                String currentUrl = page.url();
                System.out.println("Current URL: " + currentUrl);

                boolean needsLogin = currentUrl.contains("authserver")
                        || page.locator("#loginIframe").count() > 0
                        || page.locator("input").count() > 0;

                if (needsLogin) {
                    System.out.println("Authentication page detected. Proceeding to log in...");

                    // 门户登录表单固定嵌在 #loginIframe 中，这里只支持该结构。
                    // 若认证页出现却找不到该 iframe，说明页面结构已变，明确失败而不是走一条无效路径。
                    if (page.locator("#loginIframe").count() == 0) {
                        System.err.println("Authentication page has no #loginIframe. Page structure may have changed.");
                        try {
                            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("debug_no_iframe.png")));
                            System.out.println("Saved 'debug_no_iframe.png' for diagnosis.");
                        } catch (Exception ignored) {
                        }
                        throw new IllegalStateException("Login iframe (#loginIframe) not found on authentication page.");
                    }

                    System.out.println("Login iframe (#loginIframe) detected. Switching context to iframe...");
                    FrameLocator iframe = page.frameLocator("#loginIframe");
                    performLogin(iframe, page, username, password);

                    // 等待页面重定向和加载
                    page.waitForLoadState();
                    System.out.println("Redirected URL: " + page.url());

                } else {
                    System.out.println("Already logged in or bypassed authentication page directly.");
                }

                System.out.println("Retention window: last " + RETENTION_DAYS + " natural days.");

                // 2.5 读回已有 feed（增量累积的基础）：按 guid 索引，已有条目的发布时间原样保留
                Map<String, RssItem> existingItems = loadExistingFeed(RSS_FILENAME);
                System.out.println("Loaded " + existingItems.size() + " existing items from rss.xml.");

                // 3. 导航到二级列表页面
                System.out.println("Navigating to secondary list page: " + LIST_URL);
                page.navigate(LIST_URL);
                page.waitForLoadState();
                page.waitForTimeout(2000);

                // 点击“查看更多”加载更旧的更新
                int maxClicks = 10;
                for (int c = 0; c < maxClicks; c++) {
                    Locator currentLinks = page.locator("a[href*='info'], a[href*='content'], a[href*='detail']");
                    int linkCount = currentLinks.count();
                    if (linkCount > 0) {
                        // 检查页面上最后一个（最旧的）可见条目的父元素文本
                        Locator lastLink = currentLinks.nth(linkCount - 1);
                        String parentText = (String) lastLink
                                .evaluate("el => el.parentElement ? el.parentElement.innerText : ''");

                        // 最旧的可见条目已经超出保留窗口，说明再往下翻也是更旧的，停止点击
                        LocalDate oldestDate = parseListDate(parentText);
                        if (oldestDate != null && !isWithinDays(oldestDate, RETENTION_DAYS)) {
                            System.out.println(
                                    "Oldest visible item (" + oldestDate + ") is outside retention window. Stopping clicks.");
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

                // 收集保留窗口内的候选条目。发布时间用列表页解析出的真实日期，
                // 解析不到才回退到当前时刻（只影响首次入库，之后原样保留）。
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
                            url = PORTAL_ORIGIN + url;
                        } else {
                            url = page.url().substring(0, page.url().lastIndexOf('/') + 1) + url;
                        }
                    }

                    if (processedUrls.contains(url)) {
                        continue;
                    }

                    // 检查包含该链接的父元素中的日期，只保留窗口内的条目
                    String parentText = (String) linkElement
                            .evaluate("el => el.parentElement ? el.parentElement.innerText : ''");
                    LocalDate itemDate = parseListDate(parentText);
                    if (!isWithinDays(itemDate, RETENTION_DAYS)) {
                        continue; // 超出保留窗口或无法解析日期，跳过
                    }

                    processedUrls.add(url);
                    String pubDate = formatPubDate(itemDate);
                    RssItem item = new RssItem(title, url, "Loading...", pubDate);
                    item.pubDateObj = itemDate;
                    rssItems.add(item);
                    System.out.println("Collected [" + itemDate + "]: " + title + " -> " + url);
                }

                // 3.2. 第二阶段：只为“新出现的 guid”抓取详情全文；已在旧 feed 中的条目直接复用，不重抓。
                List<RssItem> newItems = new ArrayList<>();
                for (RssItem it : rssItems) {
                    if (!existingItems.containsKey(it.url)) {
                        newItems.add(it);
                    }
                }
                System.out.println("Collected " + rssItems.size() + " items in window, "
                        + newItems.size() + " are new. Fetching full contents for new items...");

                List<RssItem> fetchedNewItems = new ArrayList<>();
                for (int i = 0; i < newItems.size(); i++) {
                    RssItem item = newItems.get(i);
                    try {
                        System.out.println("Fetching details (" + (i + 1) + "/" + newItems.size()
                                + "): " + item.title);
                        page.navigate(item.url);
                        page.waitForLoadState();
                        page.waitForTimeout(2000);

                        System.out.println("  Detail page URL: " + page.url());

                        // 检查是否被重定向到登录页面
                        if (page.url().contains("authserver") || page.url().contains("login")) {
                            System.out.println("  WARNING: Detail page redirected to login! Session may have expired.");
                            item.description = "<p>文章内容需要登录后查看。</p><p><a href=\"" + item.url + "\">点击访问原文</a></p>";
                            fetchedNewItems.add(item);
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
                            System.out.println("  Page title: " + page.title());

                            String bodyHtml = page.locator("body").innerHTML().trim();
                            if (bodyHtml.length() > 10000) {
                                bodyHtml = bodyHtml.substring(0, 10000) + "...";
                            }
                            detailContent = bodyHtml;
                        }

                        item.description = detailContent;
                        fetchedNewItems.add(item);
                    } catch (Exception e) {
                        System.err.println("Failed to fetch content for " + item.url + ": " + e.getMessage());
                        item.description = "<p>无法获取详细内容，可能页面需要额外认证或结构已变动。</p><p><a href=\"" + item.url
                                + "\">点击访问原文</a></p>";
                        fetchedNewItems.add(item);
                    }
                }

                // 4. 为新条目写独立 HTML 文件（已有 article 不重写），并生成其 GitHub Pages 链接
                File articlesDir = new File("articles");
                if (!articlesDir.exists()) {
                    articlesDir.mkdirs();
                }

                for (RssItem item : fetchedNewItems) {
                    String articleFilename = extractArticleId(item.url) + ".html";
                    String articleHtml = generateArticleHtml(item.title, item.description, item.pubDate);

                    try (FileWriter fw = new FileWriter(new File(articlesDir, articleFilename))) {
                        fw.write(articleHtml);
                    }

                    item.pagesUrl = PAGES_BASE_URL + articleFilename;
                    System.out.println("Saved article: " + articleFilename);
                }

                // 5. 合并旧 feed + 新条目 → 裁剪保留窗口 → 排序 → 生成 RSS 2.0 XML
                List<RssItem> finalItems = mergeAndPrune(existingItems, fetchedNewItems);
                System.out.println("Final feed size after merge & prune: " + finalItems.size() + " items.");
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

    private static void switchToPasswordTab(Locator passwordTab, Locator usernameInput, Page page) {
        try {
            System.out.println("Waiting for password login tab to appear...");
            passwordTab.waitFor(new Locator.WaitForOptions().setTimeout(10000));
            page.waitForLoadState(LoadState.NETWORKIDLE);

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
    private static final Pattern WBNEWSID_PATTERN = Pattern.compile("wbnewsid=(\\d+)");

    private static String extractArticleId(String url) {
        // 优先用 wbnewsid 参数，保证同一篇文章文件名稳定；取不到才退化为 URL 哈希
        Matcher m = WBNEWSID_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
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
        // & 必须最先转义，避免二次转义已生成的实体；随后处理 < > " '
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 从一段文本中解析出发布日期。优先取带年份的 yyyy-MM-dd；
     * 只有 MM-dd 时补当前年份。解析不到合法日期返回 null。
     * 相比子串 contains 匹配，这里得到的是真正的 LocalDate，避免误判。
     */
    private static LocalDate parseListDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DATE_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            if (m.group(1) != null) { // yyyy-MM-dd
                return LocalDate.of(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            // MM-dd，补当前年份
            return LocalDate.of(
                    LocalDate.now().getYear(), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        } catch (Exception e) {
            return null; // 例如 13-45 这种非法月日
        }
    }

    /**
     * 判断日期是否落在“含今天在内、最近 days 个自然日”的窗口内。
     * days=7 表示今天及前 6 天。date 为 null 视为不在窗口内。
     */
    private static boolean isWithinDays(LocalDate date, int days) {
        return date != null && !date.isBefore(LocalDate.now().minusDays(days - 1L));
    }

    // 统一构造 RFC-822 GMT+8 格式器（SimpleDateFormat 非线程安全，每次新建）
    private static SimpleDateFormat rfc822Formatter() {
        SimpleDateFormat sdf = new SimpleDateFormat(RFC822_PATTERN, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return sdf;
    }

    /**
     * 把发布日期格式化为 RFC-822 的 pubDate 字符串（固定取当天 00:00:00 GMT+8）。
     * 固定时刻保证同一篇文章每次运行生成完全相同的字符串，避免无意义的 git diff。
     * date 为 null 时回退到当前时刻。
     */
    private static String formatPubDate(LocalDate date) {
        Date instant = (date == null) ? new Date() : Date.from(date.atStartOfDay(ZONE_CN).toInstant());
        return rfc822Formatter().format(instant);
    }

    /**
     * 把已有 feed 里的 pubDate 字符串解析回 LocalDate，用于窗口裁剪与排序。
     * 解析失败时返回今天（宁可保留也不误删已入库的条目）。
     */
    private static LocalDate parsePubDateToLocalDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return LocalDate.now();
        }
        try {
            return rfc822Formatter().parse(pubDate).toInstant().atZone(ZONE_CN).toLocalDate();
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    /**
     * 解析已有的 rss.xml，按 guid 索引读回历史条目。
     * 读回的 pubDate、全文内容原样保留，是“增量累积 + 不重复推送”的关键。
     * 文件不存在或解析失败时返回空 map（等价于全新开始）。
     */
    private static Map<String, RssItem> loadExistingFeed(String filename) {
        Map<String, RssItem> result = new LinkedHashMap<>();
        File file = new File(filename);
        if (!file.exists()) {
            return result;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // 关闭外部实体解析，避免 XXE 风险
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder().parse(file);

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element el = (Element) itemNodes.item(i);
                String guid = childText(el, "guid");
                if (guid == null || guid.isBlank()) {
                    continue;
                }
                String content = childText(el, "content:encoded");
                String pubDate = childText(el, "pubDate");

                RssItem item = new RssItem(childText(el, "title"), guid, content == null ? "" : content, pubDate);
                item.pagesUrl = childText(el, "link"); // 旧 link 已是免登录的 GitHub Pages 地址
                item.pubDateObj = parsePubDateToLocalDate(pubDate);
                result.put(guid, item);
            }
        } catch (Exception e) {
            System.err.println("Failed to parse existing rss.xml, starting fresh: " + e.getMessage());
            return new LinkedHashMap<>();
        }
        return result;
    }

    // 取某个子元素的文本内容（含 CDATA），不存在返回 null
    private static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent();
    }

    /**
     * 合并历史条目与本次新条目：
     * 1. 以 guid 去重（新条目覆盖同名旧条目）；
     * 2. 剔除发布日期超出保留窗口的条目；
     * 3. 按发布日期倒序排列，并截断到 MAX_FEED_ITEMS 条安全上限。
     */
    private static List<RssItem> mergeAndPrune(Map<String, RssItem> existing, List<RssItem> newItems) {
        Map<String, RssItem> merged = new LinkedHashMap<>(existing);
        for (RssItem it : newItems) {
            merged.put(it.url, it); // guid == url
        }

        List<RssItem> list = new ArrayList<>();
        for (RssItem it : merged.values()) {
            if (isWithinDays(it.pubDateObj, RETENTION_DAYS)) {
                list.add(it);
            }
        }

        // 发布日期倒序（新在前）；相等时保持稳定顺序
        list.sort((a, b) -> b.pubDateObj.compareTo(a.pubDateObj));

        if (list.size() > MAX_FEED_ITEMS) {
            list = new ArrayList<>(list.subList(0, MAX_FEED_ITEMS));
        }
        return list;
    }

    private static void generateRssXml(List<RssItem> items) throws IOException {
        System.out.println("Generating " + RSS_FILENAME + " with " + items.size() + " items...");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
        xml.append(
                "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">\n");
        xml.append("<channel>\n");
        xml.append("  <title>信息门户</title>\n");
        xml.append("  <link>https://jnzel.github.io/bupt-info/</link>\n");
        xml.append("  <description>BUPT信息门户RSS</description>\n");
        xml.append("  <language>zh-cn</language>\n");
        // lastBuildDate 取最新条目的 pubDate（items 已按倒序排列），而非当前时刻。
        // 这样无新条目时 rss.xml 逐字节不变 → git 无 diff → 不产生无意义的提交。
        String lastBuild = items.isEmpty() ? formatPubDate(null) : items.get(0).pubDate;
        xml.append("  <lastBuildDate>").append(lastBuild).append("</lastBuildDate>\n");
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

        try (FileWriter writer = new FileWriter(RSS_FILENAME)) {
            writer.write(xml.toString());
        }
        System.out.println("Successfully generated " + RSS_FILENAME);
    }

    private static class RssItem {
        String title;
        String url; // 同时用作 guid（门户原始 URL，稳定唯一）
        String description;
        String pubDate; // RFC-822 字符串，用于输出
        String pagesUrl; // 独立文章页面的 GitHub Pages URL
        LocalDate pubDateObj; // 解析后的发布日期，用于窗口裁剪与排序

        RssItem(String title, String url, String description, String pubDate) {
            this.title = title;
            this.url = url;
            this.description = description;
            this.pubDate = pubDate;
        }
    }
}
