package com.chat.talkMe.repository;

import com.chat.talkMe.domain.UnlockableCosmetic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UnlockableCosmeticRepository extends JpaRepository<UnlockableCosmetic, Long> {

    List<UnlockableCosmetic> findAll();

    Optional<UnlockableCosmetic> findByCode(String code);
}
