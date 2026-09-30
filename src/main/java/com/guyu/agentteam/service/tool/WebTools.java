package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.service.AnySearchService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网工具：web_search（AnySearch 搜索，匿名免费档可用）+ fetch_webpage（直连抓取网页正文）。
 * 两工具纯只读（不落盘、不改状态），豁免审批卡片，对所有智能体无条件注册；
 * 错误一律以中文可读文本返回（不抛出），让智能体能读到并向用户转述。
 * 每次调用经 {@link WebActivityListener} 上报（SSE 实时展示 + 随发言落库）。
 */
@Service
public class WebTools {

    /** 搜索结果 Markdown 里的来源 URL 行（AnySearch 固定格式：- **URL**: https://…） */
    private static final Pattern RESULT_URL = Pattern.compile("\\*\\*URL\\*\\*:\\s*(https?://[^/\\s)]+)");

    /** 联网活动上报：SSE 推送 + 收集落库，由 ConversationStreamSupport.webListener 提供 */
    public interface WebActivityListener {
        void onActivity(Map<String, Object> activity);
    }

    /** 独立客户端：必须跟随重定向（http→https、补斜杠很常见）；自定义 UA 避免默认 Java UA 被 403 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_MAX_CHARS = 6000;
    private static final int HARD_MAX_CHARS = 20000;

    private final AnySearchService anySearch;

    public WebTools(AnySearchService anySearch) {
        this.anySearch = anySearch;
    }

    /** 联网能力零配置可用（搜索匿名档），始终注册；listener 仅供展示与留痕，可为 null */
    public void register(Toolkit toolkit, WebActivityListener listener) {
        toolkit.registerTool(new WebTool(anySearch, listener));
    }

    public static class WebTool {

        private final AnySearchService anySearch;
        private final WebActivityListener listener;

        WebTool(AnySearchService anySearch, WebActivityListener listener) {
            this.anySearch = anySearch;
            this.listener = listener;
        }

        private void report(Map<String, Object> activity) {
            if (listener != null) listener.onActivity(activity);
        }

        @Tool(name = "web_search", description = "Search the web for up-to-date information (news, docs, "
                + "facts beyond your knowledge). Returns markdown results with titles, URLs and snippets. "
                + "Read the snippets first; use fetch_webpage on a promising URL only when you need the "
                + "full page content.", readOnly = true, concurrencySafe = true)
        public String webSearch(
                @ToolParam(name = "query", description = "Search query with ONE intent; natural language or keywords")
                String query,
                @ToolParam(name = "max_results", required = false,
                        description = "Number of results, 1-10. Optional, defaults to 5")
                Integer maxResults) {
            if (query == null || query.isBlank()) {
                return "搜索失败：query 不能为空。";
            }
            String q = query.trim();
            int n = maxResults == null ? 5 : Math.max(1, Math.min(10, maxResults));
            report(Map.of("tool", "web_search", "phase", "start", "query", q));
            try {
                String result = anySearch.search(q, n);
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("tool", "web_search");
                end.put("phase", "end");
                end.put("query", q);
                end.put("sites", resultSites(result));
                end.put("ok", true);
                report(end);
                return result;
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("tool", "web_search");
                end.put("phase", "end");
                end.put("query", q);
                end.put("ok", false);
                end.put("error", reason.length() > 200 ? reason.substring(0, 200) + "…" : reason);
                report(end);
                return "搜索失败：" + reason
                        + "。可换一组更简洁的关键词重试一次；若持续失败（如匿名额度限流），"
                        + "请告知用户可在 设置→联网搜索 配置 AnySearch API Key。";
            }
        }

        /** 从搜索结果 Markdown 提取来源站点域名（去重保序，最多 10 个） */
        private static List<String> resultSites(String markdown) {
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            Matcher m = RESULT_URL.matcher(markdown == null ? "" : markdown);
            while (m.find() && hosts.size() < 10) {
                hosts.add(URI.create(m.group(1)).getHost());
            }
            return new ArrayList<>(hosts);
        }

        @Tool(name = "fetch_webpage", description = "Fetch an http(s) web page and return its main readable "
                + "text content (scripts, styles and navigation removed). Use it to read the full content "
                + "of a URL, e.g. a search result link or a user-provided page.", readOnly = true,
                concurrencySafe = true)
        public String fetchWebpage(
                @ToolParam(name = "url", description = "Full http(s) URL of the page, e.g. https://example.com/article")
                String url,
                @ToolParam(name = "max_chars", required = false,
                        description = "Max characters of text to return, defaults to 6000, hard cap 20000")
                Integer maxChars) {
            int limit = maxChars == null ? DEFAULT_MAX_CHARS : Math.max(200, Math.min(HARD_MAX_CHARS, maxChars));
            if (url == null || url.isBlank()) {
                return "抓取失败：url 不能为空。";
            }
            URI uri;
            try {
                uri = URI.create(url.trim());
            } catch (Exception e) {
                return "抓取失败：URL 格式不正确：" + url.trim();
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "抓取失败：仅支持 http/https 链接：" + url.trim();
            }
            String target = uri.toString();
            report(Map.of("tool", "fetch_webpage", "phase", "start", "url", target));
            HttpResponse<byte[]> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(FETCH_TIMEOUT)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .GET()
                        .build();
                response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                reportFetchEnd(target, null, false, "请求网页出错：" + reason);
                return "抓取失败：请求网页出错（" + reason + "）。请确认 URL 可公开访问后重试。";
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                reportFetchEnd(target, null, false, "HTTP " + response.statusCode());
                return "抓取失败：网页返回 HTTP " + response.statusCode()
                        + "（可能需要登录、已下线或反爬拦截），可换其他来源。";
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String lower = contentType.toLowerCase();
            if (lower.startsWith("image/") || lower.contains("pdf") || lower.contains("video/")
                    || lower.contains("audio/")) {
                reportFetchEnd(target, null, false, "非网页内容 " + contentType);
                return "抓取失败：该链接不是网页（Content-Type: " + contentType + "），无法提取文本。";
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            try {
                // 必须从字节流解析：jsoup 自动识别 BOM 与 <meta charset>，GBK/GB2312 老站点不乱码
                Document doc = Jsoup.parse(new ByteArrayInputStream(body), null, uri.toString());
                String title = doc.title() == null ? "" : doc.title().trim();
                String text = extractText(doc);
                if (text.isBlank()) {
                    reportFetchEnd(target, title, false, "未解析出正文");
                    return "抓取失败：页面未解析出正文（可能是纯脚本渲染的站点），可换其他来源。";
                }
                reportFetchEnd(target, title, true, null);
                return truncate(text, limit);
            } catch (Exception e) {
                reportFetchEnd(target, null, false, "解析出错");
                return "抓取失败：解析网页出错（" + e.getMessage() + "）。";
            }
        }

        private void reportFetchEnd(String url, String title, boolean ok, String error) {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("tool", "fetch_webpage");
            end.put("phase", "end");
            end.put("url", url);
            if (title != null && !title.isBlank()) end.put("title", title.length() > 120 ? title.substring(0, 120) + "…" : title);
            end.put("ok", ok);
            if (error != null) end.put("error", error.length() > 200 ? error.substring(0, 200) + "…" : error);
            report(end);
        }

        /** 正文提取：剔除脚本/导航等噪声后，优先语义化容器按块拼接；块太少退化为整体 text() */
        private String extractText(Document doc) {
            doc.select("script,style,noscript,svg,iframe,canvas,nav,footer,header,aside,form,button,"
                    + "select,input,textarea,link,meta").remove();
            Element root = doc.body() != null ? doc.body() : doc;
            Element main = root.selectFirst("article, main, [role=main], #content, .content");
            if (main != null) root = main;
            String title = doc.title() == null ? "" : doc.title().trim();
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) sb.append("# ").append(title).append("\n\n");
            Elements blocks = root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,dd,dt");
            String last = null;
            for (Element b : blocks) {
                String t = b.text().trim();
                if (t.isEmpty() || t.equals(last)) continue;
                last = t;
                sb.append(t).append('\n');
            }
            String structured = sb.toString().trim();
            if (structured.length() < 200) {
                String plain = root.text().trim();
                if (plain.length() > structured.length()) return plain;
            }
            return structured;
        }

        private String truncate(String text, int limit) {
            if (text.length() <= limit) return text;
            int cut = limit;
            int nl = text.lastIndexOf('\n', limit);
            if (nl > limit * 3 / 4) cut = nl;
            return text.substring(0, cut).trim()
                    + "\n\n…[内容已截断，全文约 " + text.length() + " 字符，可调大 max_chars 获取更多]";
        }
    }
}
