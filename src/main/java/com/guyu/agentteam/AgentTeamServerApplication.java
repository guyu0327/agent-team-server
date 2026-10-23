package com.guyu.agentteam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentTeamServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentTeamServerApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
