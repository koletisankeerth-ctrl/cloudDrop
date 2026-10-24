package com.clouddrop.repository;

import com.clouddrop.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data generates the implementation at startup from the method names.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
