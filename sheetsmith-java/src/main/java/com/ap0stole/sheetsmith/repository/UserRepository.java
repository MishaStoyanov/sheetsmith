package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByName(String name);

    boolean existsByName(String name);

    /** A null keyword means everyone, so the caller does not need two methods for one screen. */
    @Query("select u from User u where :keyword is null or lower(u.name) like lower(concat('%', :keyword, '%'))")
    Page<User> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * The first account, which is the one that cannot be deleted. Asked for by id rather than by
     * the name "admin" because the account can be renamed, and a rule a rename can switch off is
     * not a rule.
     */
    @Query("select min(u.id) from User u")
    Long findFirstIdOrderById();
}
