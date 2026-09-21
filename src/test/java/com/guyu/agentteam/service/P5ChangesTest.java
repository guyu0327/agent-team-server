package com.guyu.agentteam.service;

import com.guyu.agentteam.entity.AgentMemory;
import com.guyu.agentteam.repository.AgentMemoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** P5 改动的可自动化验证：A4 记忆检索过滤/预算、A15 clamp 对称化 */
class P5ChangesTest {

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    // ---------- A4：关键词切分 ----------

    private static List<String> keywordsOf(String text) throws Exception {
        Method m = AgentMemoryService.class.getDeclaredMethod("keywordsOf", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) m.invoke(null, text);
        return list;
    }

    @Test
    void keywordsSplitAndMinLength() throws Exception {
        assertEquals(List.of("小明", "看一下", "项目群"), keywordsOf("请@小明 看一下，项目群！"));
        assertEquals(List.of("call", "XiaoMing", "please"), keywordsOf("call XiaoMing, please"));
        assertTrue(keywordsOf(null).isEmpty());
        assertTrue(keywordsOf("的 了 吗").isEmpty());
    }

    // ---------- A4：检索过滤与预算 ----------

    private AgentMemory mem(String content, long at) {
        AgentMemory m = new AgentMemory();
        m.setContent(content);
        m.setCreatedAt(at);
        return m;
    }

    @Test
    void retrieveFiltersByKeywordsAndFallsBack() throws Exception {
        AgentMemoryRepository repo = Mockito.mock(AgentMemoryRepository.class);
        when(repo.findByAgentIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of(
                mem("用户偏好深色界面", 1),
                mem("用户在做智群桌面版", 2),
                mem("用户喜欢喝咖啡", 3),
                mem("部署采用 SQLite", 4)));
        AgentMemoryService svc = new AgentMemoryService(repo);
        String out = (String) invoke(svc, "retrieve", new Class<?>[]{String.class, String.class},
                "a1", "桌面版 是什么");
        assertTrue(out.contains("桌面版"), "应命中含查询词的记录：" + out);
        assertTrue(!out.contains("咖啡"), "不应返回无关记录：" + out);

        String all = (String) invoke(svc, "retrieve", new Class<?>[]{String.class, String.class},
                "a1", "");
        assertTrue(all.contains("咖啡") && all.contains("SQLite"), "无查询词回退全部：" + all);

        String miss = (String) invoke(svc, "retrieve", new Class<?>[]{String.class, String.class},
                "a1", "完全无关的词xyz");
        assertTrue(miss.contains("条"), "全部不命中时回退最新几条而非空手而归：" + miss);
    }

    @Test
    void retrieveBudgetKeepsNewest() throws Exception {
        StringBuilder big = new StringBuilder("x".repeat(7000));
        AgentMemoryRepository repo = Mockito.mock(AgentMemoryRepository.class);
        when(repo.findByAgentIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of(
                mem("旧记录" + big, 1), mem("新记录" + big, 2)));
        AgentMemoryService svc = new AgentMemoryService(repo);
        String out = (String) invoke(svc, "retrieve", new Class<?>[]{String.class, String.class},
                "a1", "");
        assertTrue(out.contains("新记录"), "预算内应保留最新一条：" + out);
        assertTrue(!out.contains("旧记录"), "超预算较旧一条应被裁掉：" + out);
    }

    // ---------- A15：clamp 对称化 ----------

    private int clamp(int v) throws Exception {
        ContextCompressionService svc = new ContextCompressionService(
                null, null, null, null, null, null, null, null);
        return (Integer) invoke(svc, "clamp", new Class<?>[]{int.class}, v);
    }

    @Test
    void clampSymmetric() throws Exception {
        assertEquals(5000, clamp(100));
        assertEquals(5000, clamp(4999));
        assertEquals(500000, clamp(999999));
        assertEquals(60000, clamp(60000));
    }
}
