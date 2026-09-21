package com.guyu.agentteam.dto;

/**
 * 微信扫码登录状态：idle 无进行中登录 / qr_ready 待扫码（qrSvg 为 SVG data URL）/
 * scaned 已扫码 / need_verifycode 等配对码 / confirmed 连接成功 / failed 失败（error 给原因）
 */
public record WechatLoginDto(String status, String qrSvg, String error, String botId) {
}
