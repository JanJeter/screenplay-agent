package com.urke.saasbackendstarter.repository;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.domain.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByIdAndOrganizationIdAndDeletedFalse(Long id, Long organizationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.organization.id = :org and u.deleted = false")
    Optional<User> findLockedByIdAndOrganizationIdAndDeletedFalse(@Param("id") Long id, @Param("org") Long organizationId);
    @Query("select u from User u where u.organization.id = :org and u.deleted = false "
            + "and (lower(u.email) like lower(concat('%', :q, '%')) or lower(u.fullName) like lower(concat('%', :q, '%')))")
    Page<User> searchInOrganization(@Param("org") Long organizationId, @Param("q") String query, Pageable pageable);
    @Query("select count(distinct u.id) from User u join u.roles r where u.organization.id = :org "
            + "and u.deleted = false and u.enabled = true and u.emailVerified = true and r.name = 'ADMIN' and r.organization.id = :org")
    long countActiveAdmins(@Param("org") Long organizationId);
    Optional<User> findByEmailAndDeletedFalse(String email);
    boolean existsByEmailAndDeletedFalse(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByEmailIgnoreCaseAndDeletedFalse(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(@Param("id") Long id);
    
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.organization WHERE u.email = :email AND u.deleted = false")
    Optional<User> findByEmailWithOrganization(@Param("email") String email);
    
    List<User> findAllByOrganizationAndDeletedFalse(Organization organization);
    List<User> findAllByDeletedFalse();
    Optional<User> findByIdAndDeletedFalse(Long id);


    // Paginated and filtered
    Page<User> findAllByDeletedFalse(Pageable pageable);
    Page<User> findByEmailContainingIgnoreCaseAndDeletedFalse(String email, Pageable pageable);
}
