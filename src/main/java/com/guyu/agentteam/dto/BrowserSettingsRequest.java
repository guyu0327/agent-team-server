package com.guyu.agentteam.dto;

import java.util.List;

/**
 * 浏览器自动化设置更新入参：headless=false 时弹出真实浏览器窗口供用户围观 AI 操作；
 * whitelist 为域名白名单，命中（含子域名）的网页打开不再弹审批卡片
 */
public record BrowserSettingsRequest(boolean headless, List<String> whitelist) {
}
