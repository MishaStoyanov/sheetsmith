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

    /**
     * Names matching a fragment; an empty keyword matches everyone, so one screen needs one method.
     * <p>
     * The keyword is never null. It used to be, with an {@code :keyword is null or ...} guard, and
     * that fails at runtime on PostgreSQL: a null parameter arrives untyped, the driver infers
     * {@code bytea}, and {@code lower(bytea)} does not exist. Nothing caught it because the service
     * test mocks this repository, so the query was never executed — it took loading the screen.
     * Passing an empty string instead makes the pattern {@code %%}, which matches every row.
     */
    @Query("select u from User u where lower(u.name) like lower(concat('%', :keyword, '%'))")
    Page<User> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * The first account, which is the one that cannot be deleted. Asked for by id rather than by
     * the name "admin" because the account can be renamed, and a rule a rename can switch off is
     * not a rule.
     */
    @Query("select min(u.id) from User u")
    Long findFirstIdOrderById();
}
