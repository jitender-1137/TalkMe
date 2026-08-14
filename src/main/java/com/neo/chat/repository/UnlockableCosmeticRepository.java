package com.neo.chat.repository;

import com.neo.chat.domain.UnlockableCosmetic;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UnlockableCosmeticRepository extends JpaRepository<UnlockableCosmetic, Long> {

    @NonNull
    List<UnlockableCosmetic> findAll();

    Optional<UnlockableCosmetic> findByCode(String code);
}
