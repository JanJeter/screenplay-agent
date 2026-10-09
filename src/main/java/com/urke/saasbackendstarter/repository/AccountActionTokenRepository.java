package com.urke.saasbackendstarter.repository;

import com.urke.saasbackendstarter.domain.AccountActionToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AccountActionTokenRepository extends JpaRepository<AccountActionToken, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountActionToken> findByTokenHashAndPurpose(String tokenHash, AccountActionToken.Purpose purpose);
    @Query("select t.user.id from AccountActionToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<Long> findOwnerId(@Param("hash") String hash, @Param("purpose") AccountActionToken.Purpose purpose);
    void deleteByUserIdAndPurpose(Long userId, AccountActionToken.Purpose purpose);
}
