package com.guyu.agentteam.dto;

import com.guyu.agentteam.service.qq.QqChannelService;

import java.util.List;

/** QQ 通道状态（弹层一次拉全）：连接态 + 凭证摘要 + 全量设置 + 最近联系人（seen） */
public record QqStatusDto(boolean connected, boolean enabled, String appId, String savedAt,
                          String ownerOpenid, String agentId, boolean autoWrite, boolean autoShell,
                          int maxReplyChars, int roundTimeoutMinutes,
                          String c2cPolicy, List<String> c2cWhitelist,
                          String groupPolicy, List<String> groupWhitelist,
                          List<QqChannelService.SeenPeer> seen) {
}
