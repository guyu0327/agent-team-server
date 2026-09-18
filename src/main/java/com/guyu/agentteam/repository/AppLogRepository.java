package com.guyu.agentteam.repository;

import com.guyu.agentteam.entity.AppLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AppLogRepository extends JpaRepository<AppLog, String> {

    @Query("""
            select l from AppLog l
            where (:type is null or l.type = :type)
              and l.createdAt >= :from and l.createdAt <= :to
            order by l.createdAt desc, l.id desc
            """)
    List<AppLog> search(@Param("type") String type, @Param("from") long from,
                        @Param("to") long to, Pageable pageable);

    @Query("""
            select count(l) from AppLog l
            where (:type is null or l.type = :type)
              and l.createdAt >= :from and l.createdAt <= :to
            """)
    long countSearch(@Param("type") String type, @Param("from") long from, @Param("to") long to);

    @Modifying
    @Query("delete from AppLog l where l.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") long before);
}
