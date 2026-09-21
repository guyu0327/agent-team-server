package com.guyu.agentteam.service.wechat;

import io.nayuki.qrcodegen.QrCode;

/** 二维码 → SVG 字符串（白底黑码 + 静区），前端 <img> 直接展示，无需图形库 */
public final class QrSvg {

    private static final int QUIET_ZONE = 4;

    private QrSvg() {
    }

    public static String render(String content) {
        QrCode qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM);
        int size = qr.size + QUIET_ZONE * 2;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(size).append(' ')
                .append(size).append("\" shape-rendering=\"crispEdges\" width=\"").append(size * 8)
                .append("\" height=\"").append(size * 8).append("\">")
                .append("<rect width=\"").append(size).append("\" height=\"").append(size)
                .append("\" fill=\"#ffffff\"/>");
        sb.append("<path fill=\"#000000\" d=\"");
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    sb.append('M').append(x + QUIET_ZONE).append(',').append(y + QUIET_ZONE)
                            .append("h1v1h-1z");
                }
            }
        }
        sb.append("\"/></svg>");
        return sb.toString();
    }
}
