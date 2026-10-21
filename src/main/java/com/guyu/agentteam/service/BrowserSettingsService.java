package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 浏览器自动化设置：持久化于 app_settings（key=browser.automation）。
 * headless：默认 false=弹出真实浏览器窗口，操作过程可见（避免误以为卡住）；开启后后台静默运行。
 * persistLogin：默认 false=每次会话全新身份；开启后会话关闭时把 cookie/storage 存盘、新会话恢复（网站视角是回头客）。
 * whitelist：域名白名单（如 example.com），命中（含子域名）的 browser_open 不再弹审批卡片。
 */
@Service
public class BrowserSettingsService {

    private static final String KEY = "browser.automation";

    private final SettingsStore store;
    private final ObjectMapper mapper = Json.mapper();

    public BrowserSettingsService(SettingsStore store) {
        this.store = store;
    }

    public record BrowserSettings(boolean headless, boolean persistLogin, List<String> whitelist) {
    }

    public BrowserSettings load() {
        String json = store.read(KEY);
        if (json == null) {
            return new BrowserSettings(false, false, List.of());
        }
        try {
            BrowserSettings raw = mapper.readValue(json, BrowserSettings.class);
            return new BrowserSettings(raw.headless(), raw.persistLogin(), normalize(raw.whitelist()));
        } catch (Exception e) {
            return new BrowserSettings(false, false, List.of());
        }
    }

    public BrowserSettings save(boolean headless, boolean persistLogin, List<String> whitelist) {
        String json;
        try {
            json = mapper.writeValueAsString(new BrowserSettings(headless, persistLogin, normalize(whitelist)));
        } catch (Exception e) {
            throw new ApiException(500, "保存浏览器设置失败");
        }
        store.write(KEY, json);
        return new BrowserSettings(headless, persistLogin, normalize(whitelist));
    }

    /** 从 URL 提取小写主机名；解析失败返回 null */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** URL 的主机是否命中白名单（条目本身或其子域名；不含协议的裸主机名也按主机处理） */
    public boolean isWhitelisted(String url) {
        String host = url == null ? null : (url.contains("://") ? hostOf(url)
                : url.trim().toLowerCase(Locale.ROOT));
        if (host == null || host.isBlank()) {
            return false;
        }
        for (String entry : load().whitelist()) {
            if (host.equals(entry) || host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /** 加入白名单（去重规整后落库），返回新列表 */
    public List<String> addDomain(String host) {
        if (host == null || host.isBlank()) {
            return load().whitelist();
        }
        BrowserSettings cur = load();
        List<String> next = new ArrayList<>(cur.whitelist());
        next.add(host.toLowerCase(Locale.ROOT));
        return save(cur.headless(), cur.persistLogin(), next).whitelist();
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.contains("://")) {
                String host = hostOf(t);
                t = host == null ? "" : host;
            }
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return List.copyOf(set);
    }
}
