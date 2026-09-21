package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.WechatLoginDto;
import com.guyu.agentteam.dto.WechatSettingsRequest;
import com.guyu.agentteam.dto.WechatStatusDto;
import com.guyu.agentteam.service.wechat.WechatChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 微信 iLink Bot 通道：状态 / 扫码登录 / 通道设置 */
@RestController
@RequestMapping("/api/wechat")
public class WechatController {

    private final WechatChannelService channel;

    public WechatController(WechatChannelService channel) {
        this.channel = channel;
    }

    @GetMapping("/status")
    public WechatStatusDto status() {
        var s = channel.status();
        return new WechatStatusDto(s.connected(), s.enabled(), s.botId(), s.ownerUserId(),
                s.loginInProgress(), s.settings().agentId(), s.settings().autoWrite(),
                s.settings().autoShell(), s.settings().maxReplyChars(), s.settings().roundTimeoutMinutes());
    }

    @PostMapping("/login")
    public WechatLoginDto startLogin() {
        var s = channel.startLogin();
        return new WechatLoginDto(s.status(), svgDataUrl(s.qrSvg()), s.error(), s.botId());
    }

    /** 前端 1-2 秒轮询一次；qrSvg 只在存在时返回，避免每次轮询都拖几 KB */
    @GetMapping("/login/status")
    public WechatLoginDto loginStatus() {
        var s = channel.loginState();
        return new WechatLoginDto(s.status(), svgDataUrl(s.qrSvg()), s.error(), s.botId());
    }

    @PostMapping("/login/verify")
    public WechatLoginDto submitVerifyCode(@RequestBody Map<String, String> body) {
        channel.submitVerifyCode(body.getOrDefault("code", ""));
        var s = channel.loginState();
        return new WechatLoginDto(s.status(), svgDataUrl(s.qrSvg()), s.error(), s.botId());
    }

    @PostMapping("/login/cancel")
    public WechatLoginDto cancelLogin() {
        channel.cancelLogin();
        return new WechatLoginDto("idle", null, null, null);
    }

    @PostMapping("/disconnect")
    public WechatStatusDto disconnect() {
        channel.disconnect();
        return status();
    }

    @PutMapping("/settings")
    public WechatStatusDto updateSettings(@RequestBody WechatSettingsRequest req) {
        var saved = channel.saveSettings(new WechatChannelService.ChannelSettings(
                req.enabled(), req.agentId(), req.autoWrite(), req.autoShell(),
                req.maxReplyChars(), req.roundTimeoutMinutes()));
        var s = channel.status();
        return new WechatStatusDto(s.connected(), saved.enabled(), s.botId(), s.ownerUserId(),
                s.loginInProgress(), saved.agentId(), saved.autoWrite(), saved.autoShell(),
                saved.maxReplyChars(), saved.roundTimeoutMinutes());
    }

    private static String svgDataUrl(String svg) {
        if (svg == null || svg.isBlank()) {
            return null;
        }
        return "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(
                svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
