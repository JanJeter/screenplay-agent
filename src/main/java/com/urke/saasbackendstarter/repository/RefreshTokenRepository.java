package com.urke.saasbackendstarter.repository;

import com.urke.saasbackendstarter.domain.RefreshToken;
import com.urke.saasbackendstarter.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByToken(String token);
    @Query("select r.user.id from RefreshToken r where r.token = :token")
    Optional<Long> findOwnerId(@Param("token") String token);
    int deleteByUser(User user);
}
