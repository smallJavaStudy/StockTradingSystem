package com.stock.controller;

import com.stock.entity.InvestNote;
import com.stock.repository.InvestNoteRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 投资笔记 CRUD：/api/stock/{code}/notes。
 * 分类 5 类（投资逻辑/风险点/买入条件/卖出条件/自由笔记），content 1-10000 字。
 */
@RestController
@RequestMapping("/api/stock/{code}/notes")
public class InvestNoteController {

    /** content 最大长度 */
    static final int MAX_CONTENT_LENGTH = 10000;

    private final InvestNoteRepository noteRepo;

    public InvestNoteController(InvestNoteRepository noteRepo) {
        this.noteRepo = noteRepo;
    }

    /** 列表；category 可选过滤 */
    @GetMapping
    public ResponseEntity<List<InvestNote>> list(@PathVariable String code,
                                                 @RequestParam(required = false) String category) {
        if (category == null || category.isBlank()) {
            return ResponseEntity.ok(noteRepo.findByCodeOrderByUpdatedAtDesc(code));
        }
        return ResponseEntity.ok(noteRepo.findByCodeAndCategoryOrderByUpdatedAtDesc(code, parseCategory(category)));
    }

    @PostMapping
    public ResponseEntity<InvestNote> create(@PathVariable String code, @RequestBody InvestNote note) {
        validateContent(note.getContent());
        if (note.getCategory() == null) {
            throw new IllegalArgumentException("笔记分类不能为空");
        }
        InvestNote entity = new InvestNote(code, note.getCategory(), note.getContent());
        return ResponseEntity.ok(noteRepo.save(entity));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InvestNote> update(@PathVariable String code, @PathVariable Long id,
                                             @RequestBody InvestNote patch) {
        InvestNote existing = noteRepo.findById(id)
                .filter(n -> n.getCode().equals(code))
                .orElseThrow(() -> new NoSuchElementException("笔记不存在: " + id));
        if (patch.getContent() != null) {
            validateContent(patch.getContent());
            existing.setContent(patch.getContent());
        }
        if (patch.getCategory() != null) {
            existing.setCategory(patch.getCategory());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(noteRepo.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String code, @PathVariable Long id) {
        InvestNote existing = noteRepo.findById(id)
                .filter(n -> n.getCode().equals(code))
                .orElseThrow(() -> new NoSuchElementException("笔记不存在: " + id));
        noteRepo.delete(existing);
        return ResponseEntity.noContent().build();
    }

    private void validateContent(String content) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("笔记内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
    }

    private InvestNote.Category parseCategory(String category) {
        try {
            return InvestNote.Category.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法笔记分类: " + category);
        }
    }
}
