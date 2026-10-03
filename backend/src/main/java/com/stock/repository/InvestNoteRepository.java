package com.stock.repository;

import com.stock.entity.InvestNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestNoteRepository extends JpaRepository<InvestNote, Long> {

    List<InvestNote> findByCodeOrderByUpdatedAtDesc(String code);

    List<InvestNote> findByCodeAndCategoryOrderByUpdatedAtDesc(String code, InvestNote.Category category);
}
