package com.stock.controller;

import com.stock.entity.InvestNote;
import com.stock.repository.InvestNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link InvestNoteController} 单元测试：mock {@link InvestNoteRepository}（不依赖数据库），
 * 覆盖列表（含分类过滤与非法分类）、新增（content 1-10000 校验）、更新（跨股票防护）、删除。
 */
class InvestNoteControllerTest {

    private static final String CODE = "300364";

    private InvestNoteRepository repo;
    private InvestNoteController controller;

    private InvestNote saved;

    @BeforeEach
    void setUp() {
        repo = mock(InvestNoteRepository.class);
        controller = new InvestNoteController(repo);

        saved = new InvestNote(CODE, InvestNote.Category.INVEST_LOGIC, "数字阅读龙头，AI 改编逻辑");
        saved.setId(1L);

        when(repo.save(any(InvestNote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────── 查询 ───────────────────

    @Nested
    @DisplayName("GET 列表")
    class ListNotes {

        @Test
        @DisplayName("不带分类：返回该股全部笔记（按更新时间倒序）")
        void list_all() {
            when(repo.findByCodeOrderByUpdatedAtDesc(CODE)).thenReturn(List.of(saved));

            List<InvestNote> result = controller.list(CODE, null).getBody();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCategory()).isEqualTo(InvestNote.Category.INVEST_LOGIC);
            verify(repo).findByCodeOrderByUpdatedAtDesc(CODE);
        }

        @Test
        @DisplayName("带分类过滤：只查该分类")
        void list_byCategory() {
            when(repo.findByCodeAndCategoryOrderByUpdatedAtDesc(CODE, InvestNote.Category.RISK_POINT))
                    .thenReturn(List.of());

            List<InvestNote> result = controller.list(CODE, "RISK_POINT").getBody();

            assertThat(result).isEmpty();
            verify(repo).findByCodeAndCategoryOrderByUpdatedAtDesc(CODE, InvestNote.Category.RISK_POINT);
        }

        @Test
        @DisplayName("非法分类：抛 IllegalArgumentException")
        void list_invalidCategory_throws() {
            assertThatThrownBy(() -> controller.list(CODE, "NOT_A_CATEGORY"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法笔记分类");
        }
    }

    // ─────────────────── 新增 ───────────────────

    @Nested
    @DisplayName("POST 新增")
    class Create {

        @Test
        @DisplayName("正常新增：code 取路径参数，分类内容落库")
        void create_success() {
            InvestNote input = new InvestNote();
            input.setCategory(InvestNote.Category.BUY_CONDITION);
            input.setContent("回踩 MA20 且缩量企稳后买入");

            InvestNote result = controller.create(CODE, input).getBody();

            assertThat(result.getCode()).isEqualTo(CODE);
            ArgumentCaptor<InvestNote> captor = ArgumentCaptor.forClass(InvestNote.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getContent()).isEqualTo("回踩 MA20 且缩量企稳后买入");
            assertThat(captor.getValue().getCategory()).isEqualTo(InvestNote.Category.BUY_CONDITION);
        }

        @Test
        @DisplayName("content 为空：抛 IllegalArgumentException，不落库")
        void create_emptyContent_throws() {
            InvestNote input = new InvestNote();
            input.setCategory(InvestNote.Category.FREE_NOTE);
            input.setContent("");

            assertThatThrownBy(() -> controller.create(CODE, input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("content 超过 10000 字：抛 IllegalArgumentException")
        void create_tooLongContent_throws() {
            InvestNote input = new InvestNote();
            input.setCategory(InvestNote.Category.FREE_NOTE);
            input.setContent("字".repeat(10001));

            assertThatThrownBy(() -> controller.create(CODE, input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("10000");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("content 恰好 10000 字：允许")
        void create_maxLengthContent_ok() {
            InvestNote input = new InvestNote();
            input.setCategory(InvestNote.Category.FREE_NOTE);
            input.setContent("字".repeat(10000));

            controller.create(CODE, input);

            verify(repo).save(any(InvestNote.class));
        }

        @Test
        @DisplayName("分类为空：抛 IllegalArgumentException")
        void create_nullCategory_throws() {
            InvestNote input = new InvestNote();
            input.setContent("内容");

            assertThatThrownBy(() -> controller.create(CODE, input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("分类不能为空");
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────── 更新 ───────────────────

    @Nested
    @DisplayName("PUT 更新")
    class Update {

        @Test
        @DisplayName("部分字段更新：只改传入的字段并刷新 updatedAt")
        void update_partialFields() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));
            InvestNote patch = new InvestNote();
            patch.setContent("更新后的逻辑");

            InvestNote result = controller.update(CODE, 1L, patch).getBody();

            assertThat(result.getContent()).isEqualTo("更新后的逻辑");
            assertThat(result.getCategory()).isEqualTo(InvestNote.Category.INVEST_LOGIC); // 未变
        }

        @Test
        @DisplayName("更新内容超长：抛 IllegalArgumentException，不落库")
        void update_tooLong_throws() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));
            InvestNote patch = new InvestNote();
            patch.setContent("字".repeat(10001));

            assertThatThrownBy(() -> controller.update(CODE, 1L, patch))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("id 属于其他股票：抛 NoSuchElementException（防跨股票越权）")
        void update_wrongCode_throws() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));

            assertThatThrownBy(() -> controller.update("600519", 1L, new InvestNote()))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("笔记不存在");
        }

        @Test
        @DisplayName("记录不存在：抛 NoSuchElementException")
        void update_notFound_throws() {
            when(repo.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(CODE, 404L, new InvestNote()))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    // ─────────────────── 删除 ───────────────────

    @Nested
    @DisplayName("DELETE 删除")
    class Delete {

        @Test
        @DisplayName("正常删除：返回 204")
        void delete_success() {
            when(repo.findById(1L)).thenReturn(Optional.of(saved));

            var response = controller.delete(CODE, 1L);

            assertThat(response.getStatusCode().value()).isEqualTo(204);
            verify(repo).delete(saved);
        }

        @Test
        @DisplayName("记录不存在或不属于该股票：抛 NoSuchElementException，不执行删除")
        void delete_notFound_throws() {
            when(repo.findById(404L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> controller.delete(CODE, 404L))
                    .isInstanceOf(NoSuchElementException.class);

            when(repo.findById(1L)).thenReturn(Optional.of(saved));
            assertThatThrownBy(() -> controller.delete("600519", 1L))
                    .isInstanceOf(NoSuchElementException.class);
            verify(repo, never()).delete(any(InvestNote.class));
        }
    }
}
