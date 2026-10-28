package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.QqSettingsRequest;
import com.guyu.agentteam.dto.QqStatusDto;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.service.qq.QqChannelService;
import com.guyu.agentteam.service.qq.QqQrService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** QQ 官方开放平台机器人通道：状态 / 凭证 / 扫码绑定 / 通道与权限设置 / 会话重置 */
@RestController
@RequestMapping("/api/qq")
public class QqController {

    private final QqChannelService channel;
    private final QqQrService qr;

    public QqController(QqChannelService channel, QqQrService qr) {
        this.channel = channel;
        this.qr = qr;
    }

    @GetMapping("/status")
    public QqStatusDto status() {
        return toDto(channel.status());
    }

    /** 保存凭证（AppID/AppSecret + 可选机主 openid）：后端先实测换 token，失败直接报错不落库 */
    @PostMapping("/credential")
    public QqStatusDto saveCredential(@RequestBody Map<String, String> body) {
        channel.saveCredential(body.getOrDefault("appId", ""), body.getOrDefault("appSecret", ""),
                body.getOrDefault("ownerOpenid", ""));
        return status();
    }

    @PutMapping("/settings")
    public QqStatusDto updateSettings(@RequestBody QqSettingsRequest req) {
        channel.saveSettings(new QqChannelService.ChannelSettings(
                req.enabled(), req.agentId(), req.autoWrite(), req.autoShell(),
                req.maxReplyChars(), req.roundTimeoutMinutes(),
                req.c2cPolicy(), req.c2cWhitelist(),
                req.groupPolicy(), req.groupWhitelist()));
        return status();
    }

    @PostMapping("/disconnect")
    public QqStatusDto disconnect() {
        channel.disconnect();
        return status();
    }

    /** 扫码绑定第一步：创建绑定任务，返回二维码内容链接（前端渲染成图片，手机 QQ 扫码） */
    @PostMapping("/qr/start")
    public Map<String, String> qrStart() {
        QqQrService.Start s = qr.start();
        return Map.of("taskId", s.taskId(), "qrUrl", s.qrUrl());
    }

    /** 扫码绑定轮询：pending 等待 / completed 携带凭据与机主 openid（前端据此自动保存）/ expired 换新码 */
    @PostMapping("/qr/poll")
    public Map<String, String> qrPoll(@RequestBody Map<String, String> body) {
        QqQrService.Poll p = qr.poll(body.getOrDefault("taskId", ""));
        return Map.of("status", p.status(), "appId", p.appId(), "appSecret", p.appSecret(),
                "ownerOpenid", p.userOpenid());
    }

    /** 重置 QQ 会话：旧会话归档进历史（不可恢复聊天），返回新会话 id，QQ 消息无缝流入 */
    @PostMapping("/reset")
    public Map<String, String> reset(@RequestBody QqResetRequest req) {
        Conversation fresh = channel.resetConversation(req.conversationId());
        return Map.of("conversationId", fresh.getId());
    }

    public record QqResetRequest(String conversationId) {
    }

    private static QqStatusDto toDto(QqChannelService.Status s) {
        var c = s.credential();
        var st = s.settings();
        return new QqStatusDto(s.connected(), st.enabled(),
                c == null ? null : c.appId(), c == null ? null : c.savedAt(),
                c == null ? null : c.ownerOpenid(),
                st.agentId(), st.autoWrite(), st.autoShell(),
                st.maxReplyChars(), st.roundTimeoutMinutes(),
                st.c2cPolicy(), st.c2cWhitelist(),
                st.groupPolicy(), st.groupWhitelist(),
                s.seen());
    }
}
