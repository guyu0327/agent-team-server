package com.guyu.agentteam;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:./target/test-agent-team.db")
class AgentTeamServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
