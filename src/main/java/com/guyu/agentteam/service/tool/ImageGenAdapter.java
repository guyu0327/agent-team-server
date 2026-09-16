package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.ModelPreset;

/**
 * 文生图协议适配器：把一次「提示词 → 图片字节」请求适配到具体服务商协议。
 * 实现负责发请求与下载/解码图片字节，不落盘、不感知会话。
 */
public interface ImageGenAdapter {

    /** 生成图片并返回字节与扩展名（不带点） */
    Result generate(ModelPreset preset, String prompt, String size);

    record Result(byte[] data, String ext) {
    }
}
