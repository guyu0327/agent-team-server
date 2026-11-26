package com.guyu.agentteam.service;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编排者是会话角色（conversations.host_agent_id）：被动群由他协调；悬空/未设置回落全员依次；
 * 自由讨论角色休眠（不因 host 短路接龙）。
 */
class ChatStreamServiceHostTest {

    private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);

    private final Agent alice = agent("a1", "小明");
    private final Agent bob = agent("b2", "小红");

    @BeforeEach
    void setUp() {
        when(members.findByConversationIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of(
                new ConversationMember("c1", "a1", 1L),
                new ConversationMember("c1", "b2", 2L)));
        when(agents.findById("a1")).thenReturn(Optional.of(alice));
        when(agents.findById("b2")).thenReturn(Optional.of(bob));
        // 悬空主持：hostId 指向不存在的成员/智能体
        when(agents.findById("zz")).thenReturn(Optional.empty());
    }

    private List<Agent> responders(Conversation conv, String content) throws Exception {
        ChatStreamService svc = new ChatStreamService(members, agents, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        Method m = ChatStreamService.class.getDeclaredMethod("responders",
                Conversation.class, String.class, boolean.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Agent> out = (List<Agent>) m.invoke(svc, conv, content, isFree(conv));
        return out;
    }

    private boolean isFree(Conversation conv) {
        return "group".equals(conv.getType()) && "free".equals(conv.getChatMode());
    }

    private Conversation group(String chatMode, String hostId) {
        Conversation c = new Conversation();
        c.setId("c1");
        c.setType("group");
        c.setChatMode(chatMode);
        c.setHostAgentId(hostId);
        return c;
    }

    private static Agent agent(String id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    @Test
    void passiveWithHostCoordinatesAlone() throws Exception {
        assertEquals(List.of(alice), responders(group("passive", "a1"), "大家开始吧"));
    }

    @Test
    void passiveWithoutHostRelaysAll() throws Exception {
        assertEquals(List.of(alice, bob), responders(group("passive", null), "大家开始吧"));
    }

    @Test
    void danglingHostFallsBackToRelay() throws Exception {
        assertEquals(List.of(alice, bob), responders(group("passive", "zz"), "大家开始吧"));
    }

    @Test
    void freeModeIgnoresHostEvenWhenMentioned() throws Exception {
        Conversation free = group("free", "a1");
        assertEquals(List.of(), responders(free, "大家开始吧"));
        assertEquals(List.of(alice, bob), responders(free, "@小明 @小红 聊聊"));
    }

    @Test
    void mentionHostCoordinatesMentionedOthersRelay() throws Exception {
        Conversation passive = group("passive", "a1");
        assertEquals(List.of(alice), responders(passive, "@小明 看下这个"));
        assertEquals(List.of(bob), responders(passive, "@小红 看下这个"));
        // 被点名者里有主持：主持单独协调（与旧「点名编排者」行为一致）
        assertEquals(List.of(alice), responders(passive, "@小明 @小红 一起看"));
    }

    @Test
    void isFreeDiscussionNeedsGroupAndFreeMode() throws Exception {
        ChatStreamService svc = new ChatStreamService(members, agents, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        Method m = ChatStreamService.class.getDeclaredMethod("isFreeDiscussion", Conversation.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(svc, group("free", "a1")));
        assertEquals(false, m.invoke(svc, group("passive", "a1")));
        Conversation single = group("free", "a1");
        single.setType("single");
        assertEquals(false, m.invoke(svc, single));
    }
}
