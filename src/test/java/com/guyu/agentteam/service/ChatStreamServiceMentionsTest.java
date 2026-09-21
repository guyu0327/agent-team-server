package com.guyu.agentteam.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B13：@名字 精准命中——「@小明哥」里不得误命中「小明」 */
class ChatStreamServiceMentionsTest {

    private boolean mentions(String content, String name) throws Exception {
        ChatStreamService svc = new ChatStreamService(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Method m = ChatStreamService.class.getDeclaredMethod("mentions", String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(svc, content, name);
    }

    @Test
    void exactHitAndBoundaries() throws Exception {
        assertTrue(mentions("@小明 你看下这个方案", "小明"));
        assertTrue(mentions("@小明，看下", "小明"));
        assertTrue(mentions("麻烦 @小明 和 @小明哥 一起看", "小明"));
        assertTrue(mentions("麻烦 @小明 和 @小明哥 一起看", "小明哥"));
        assertTrue(mentions("Hey @XiaoMing hi", "XiaoMing"));
    }

    @Test
    void noFalsePositiveOnLongerName() throws Exception {
        assertEquals(false, mentions("@小明哥 帮忙处理下", "小明"));
        assertEquals(false, mentions("@XiaoMing2 hi", "XiaoMing"));
        assertEquals(false, mentions("没有@任何人的消息", "小明"));
        assertEquals(false, mentions("小明", "小明"));
    }
}
