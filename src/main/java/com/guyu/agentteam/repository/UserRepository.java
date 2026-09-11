package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    User findFirstByOrderByCreatedAtAsc();
}
