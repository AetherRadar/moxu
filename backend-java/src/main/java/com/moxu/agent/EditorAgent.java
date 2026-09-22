package com.moxu.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.Draft;
import com.moxu.rewrite.PolishResult;
import com.moxu.rewrite.PolishService;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DeAiRewriteConfigStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑 Agent - 修订草稿
 */
@Slf4j
@Component
public class EditorAgent extends BaseAgent {
    
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,8}|[a-zA-Z0-9_]{3,}");
    private static final String PATCH_END_ANCHOR = "<<<Moxu_END_OF_DRAFT>>>";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryPackStorage memoryPackStorage;
    private final DeAiRewriteConfigStorage deAiRewriteConfigStorage;
    private final PolishService polishService;
    
    public EditorAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                       MemoryPackStorage memoryPackStorage,
                       DeAiRewriteConfigStorage deAiRewriteConfigStorage,
                       PolishService polishService) {
        super(llmGateway, cardStorage, draftStorage, "zh");
        this.memoryPackStorage = memoryPackStorage;
        this.deAiRewriteConfigStorage = deAiRewriteConfigStorage;
        this.polishService = polishService;
    }
    
    @Override
    public String getAgentName() {
        return "editor";
    }
    
    @Override
    public String getSystemPrompt() {
        return """
                ### 角色定位
                你是墨序 MoXu 系统的 Editor（编辑），一位经验丰富的文字修订专家。
                核心职责：根据反馈对原稿进行精准修订，保持最小改动原则。
                
                ### 专业能力
                - 擅长：精准修订、风格统一、细节把控、一致性维护
                - 工作原则：只改必改之处，保留原作神韵
                
                ==================================================
                ### 核心约束（最小改动原则）
                ==================================================
                
                【P0-必须】执行力：
                  - 必须 100% 执行用户的修改意见
                  - 改动必须可见、可验证
                
                【P0-必须】保守性：
                  - 未被提及的段落/句子必须逐字保持
                  - 禁止无故换词、调整语序、改标点、改分段
                  - 禁止「顺手润色」「全篇重写」（除非用户明确要求）
                  - 绝对禁止随意将原稿的多个短段落合并成一个大段落！保持网文短句排版
                
                【P0-必须】一致性：
                  - 禁止新增设定/剧情/人物
                  - 禁止引入与原稿矛盾的事实
                  - 保持原文文风、语气、专名/称谓一致
                
                【P0-必须】输出规范：
                  - 仅输出修改后的正文（中文）
                  - 禁止附加解释、说明、修改记录
                
                ────────────────────────────────────────
                ### 编辑策略矩阵
                
                | 反馈类型 | 处理策略 |
                |---------|---------|
                | 局部修正 | 只动相关句段，其他逐字保留 |
                | 风格调整 | 全文统一调整节奏/措辞，但不改事实 |
                | 扩写要求 | 在最相关位置插入，不重排原文结构 |
                | 删减要求 | 精准删除指定内容，保持上下文连贯 |
                | 被拒绝概念 | 必须删除或彻底改写相关表达 |
                
                ### 自检清单（内部执行）
                
                □ 用户的每一条修改要求是否都已执行？
                □ 是否存在过度改动（改了不该改的地方）？
                □ 是否引入了新信息或新矛盾？
                □ 专名、称谓是否保持一致？
                □ 文风和语气是否与原文统一？
                
                ────────────────────────────────────────
                【关键约束重复 - 请务必遵守】
                【P0-必须】100% 执行用户修改意见，改动必须可见
                【P0-必须】未被提及的内容逐字保持，禁止顺手润色
                【P0-必须】禁止合并短段落，保持网文短句排版
                【P0-必须】仅输出修改后的完整正文，禁止附加任何说明
                """;
    }
    
    /**
     * 执行编辑任务 - 修订草稿
     */
    public Map<String, Object> execute(String projectId, String chapter, Map<String, Object> context) {
        String draftVersion = (String) context.getOrDefault("draft_version", "current");
        String userFeedback = (String) context.getOrDefault("user_feedback", "");
        @SuppressWarnings("unchecked")
        List<String> rejectedEntities = (List<String>) context.getOrDefault("rejected_entities", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> memoryPack = (Map<String, Object>) context.get("memory_pack");
        
        try {
            // 1. 获取当前草稿
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, draftVersion);
            if (draftOpt.isEmpty()) {
                return Map.of("success", false, "error", "Draft not found");
            }
            
            Draft currentDraft = draftOpt.get();
            
            // 2. 构建消息
            List<Map<String, String>> messages = buildRevisionMessages(
                    projectId, currentDraft.getContent(), userFeedback, rejectedEntities, memoryPack
            );
            
            // 3. 调用 LLM
            LlmResponse response = callWriterLlm(messages);
            String revisedContent = extractRevisedContent(response.getContent());

            if (normalizeForCompare(revisedContent).equals(normalizeForCompare(currentDraft.getContent()))) {
                revisedContent = attemptQuotedSelectionFallback(
                        projectId,
                        currentDraft.getContent(),
                        userFeedback,
                        rejectedEntities,
                        memoryPack
                );
            }
            
            // 4. 保存新版本
            int newVersionNum = extractVersionNumber(draftVersion) + 1;
            String newVersion = "v" + newVersionNum;
            
            Draft newDraft = draftStorage.saveCurrentDraft(
                    projectId, chapter, revisedContent, revisedContent.length(), null, true
            );
            newDraft.setVersion(newVersion);
            
            return Map.of(
                    "success", true,
                    "draft", newDraft,
                    "version", newVersion
            );
        } catch (Exception e) {
            log.error("编辑执行失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 建议修订（不保存）- 全文修订
     */
    public String suggestRevision(String projectId, String originalDraft, String userFeedback,
                                   List<String> rejectedEntities) {
        return suggestRevision(projectId, originalDraft, userFeedback, rejectedEntities, null);
    }

    public String suggestRevision(String projectId, String originalDraft, String userFeedback,
                                  List<String> rejectedEntities, Map<String, Object> memoryPack) {
        String original = normalizeNewlines(originalDraft);
        if (userFeedback == null || userFeedback.isBlank()) {
            throw new IllegalArgumentException("编辑建议失败：instruction 不能为空");
        }
        boolean strongIntent = hasStrongEditIntent(userFeedback) || requiresChange(userFeedback);
        boolean appendIntent = hasAppendIntent(userFeedback);
        if (strongIntent && !appendIntent && prefersSelectionReplacement(userFeedback)) {
            String explicitLocated = attemptExplicitSelectionFallback(
                    projectId,
                    original,
                    userFeedback,
                    rejectedEntities,
                    memoryPack
            );
            if (explicitLocated != null && !normalizeForCompare(explicitLocated).equals(normalizeForCompare(original))) {
                return explicitLocated;
            }
        }
        List<String> contextItems = buildPatchContextItems(projectId, rejectedEntities, memoryPack);
        String excerpts = buildPatchExcerpts(original, userFeedback, memoryPack, 900, 1400, 180, 8);
        List<Map<String, String>> messages = buildPatchOpsMessages(userFeedback, excerpts, contextItems);
        String raw = "";
        try {
            LlmResponse response = callLlm(messages);
            raw = response.getContent() != null ? response.getContent().trim() : "";
            String revised = applyPatchResponse(original, raw);
            if (!normalizeForCompare(revised).equals(normalizeForCompare(original))) {
                return revised;
            }
            if (strongIntent && !appendIntent) {
                String autoSelected = attemptAutoSelectionFallback(projectId, original, userFeedback, rejectedEntities, memoryPack);
                if (autoSelected != null && !normalizeForCompare(autoSelected).equals(normalizeForCompare(original))) {
                    return autoSelected;
                }
                throw new IllegalArgumentException("patch_ops_no_effect");
            }
            if (appendIntent) {
                return attemptAppendOnlyFallback(original, userFeedback, contextItems, revised);
            }
            return revised;
        } catch (Exception e) {
            String retryExcerpts = buildPatchExcerpts(original, userFeedback, memoryPack, 1600, 2600, 240, 10);
            List<Map<String, String>> retryMessages = new ArrayList<>(messages);
            retryMessages.add(Map.of("role", "assistant", "content", raw));
            retryMessages.add(Map.of("role", "user", "content", String.join("\n",
                    "你刚才输出的补丁 ops 无法应用到原文。",
                    "错误：" + e.getMessage(),
                    "请重新输出 JSON（同 schema），并确保：",
                    "- replace/delete 的 before 必须是原文摘录中可逐字匹配的片段",
                    "- insert_* 的 anchor 必须是原文摘录中可逐字匹配的片段",
                    "- 尽量减少 ops 数量，只改必要位置",
                    "- 必须至少输出 1 条 op，且应用后必须产生可见改动",
                    "",
                    "原文摘录（请仅从这里复制粘贴 before/anchor）：",
                    "<<<EXCERPTS_START>>>",
                    retryExcerpts,
                    "<<<EXCERPTS_END>>>",
                    "现在重新输出："
            )));
            String raw2 = "";
            try {
                LlmResponse retryResponse = callWriterLlm(retryMessages);
                raw2 = retryResponse.getContent() != null ? retryResponse.getContent().trim() : "";
                String revised2 = applyPatchResponse(original, raw2);
                if (!normalizeForCompare(revised2).equals(normalizeForCompare(original))) {
                    return revised2;
                }
                if (requiresChange(userFeedback) && !appendIntent) {
                    String autoSelected = attemptAutoSelectionFallback(projectId, original, userFeedback, rejectedEntities, memoryPack);
                    if (autoSelected != null && !normalizeForCompare(autoSelected).equals(normalizeForCompare(original))) {
                        return autoSelected;
                    }
                }
                if (strongIntent && !appendIntent) {
                    throw new IllegalArgumentException("未能生成可应用的修改补丁：请在指令中直接引用（复制粘贴）要修改的原句或段落，或提供更具体的定位关键词。");
                }
                if (appendIntent) {
                    return attemptAppendOnlyFallback(original, userFeedback, contextItems, revised2);
                }
                return revised2;
            } catch (Exception retryError) {
                if (appendIntent) {
                    return attemptAppendOnlyFallback(original, userFeedback, contextItems, original);
                }
                String quoted = attemptQuotedSelectionFallback(projectId, original, userFeedback, rejectedEntities, memoryPack);
                if (!normalizeForCompare(quoted).equals(normalizeForCompare(original))) {
                    return quoted;
                }
                if (retryError instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(retryError);
            }
        }
    }

    /**
     * 选区编辑（子串匹配方式）- 对齐 Python suggest_revision_selection
     */
    public String suggestRevisionSelection(String projectId, String originalDraft,
                                            String selectionText, String userFeedback,
                                            List<String> rejectedEntities) {
        return suggestRevisionSelection(projectId, originalDraft, selectionText, 1, userFeedback, rejectedEntities, null);
    }

    public String suggestRevisionSelection(String projectId, String originalDraft,
                                           String selectionText, String userFeedback,
                                           List<String> rejectedEntities, Map<String, Object> memoryPack) {
        return suggestRevisionSelection(projectId, originalDraft, selectionText, 1, userFeedback, rejectedEntities, memoryPack);
    }

    public String suggestRevisionSelection(String projectId, String originalDraft,
                                           String selectionText, int selectionOccurrence,
                                           String userFeedback,
                                           List<String> rejectedEntities, Map<String, Object> memoryPack) {
        if (selectionText == null || selectionText.isBlank()) {
            throw new IllegalArgumentException("选区编辑失败：选区为空");
        }
        String original = normalizeNewlines(originalDraft);
        String sel = normalizeNewlines(selectionText);
        int occurrence = Math.max(1, selectionOccurrence);
        int startCursor = 0;
        int pos = -1;
        for (int i = 0; i < occurrence; i++) {
            pos = original.indexOf(sel, startCursor);
            if (pos < 0) {
                break;
            }
            startCursor = pos + Math.max(1, sel.length());
        }
        if (pos < 0) {
            throw new IllegalArgumentException("选区编辑失败：未在正文中找到选区文本（请重新选中或缩小选区）");
        }
        return suggestRevisionSelectionRange(projectId, original, pos, pos + sel.length(),
                sel, userFeedback, rejectedEntities, memoryPack);
    }

    /**
     * 选区编辑（精确偏移方式）- 对齐 Python suggest_revision_selection_range
     */
    public String suggestRevisionSelectionRange(String projectId, String originalDraft,
                                                 int selectionStart, int selectionEnd,
                                                 String selectionText, String userFeedback,
                                                 List<String> rejectedEntities) {
        return suggestRevisionSelectionRange(projectId, originalDraft, selectionStart, selectionEnd,
                selectionText, userFeedback, rejectedEntities, null);
    }

    public String suggestRevisionSelectionRange(String projectId, String originalDraft,
                                                int selectionStart, int selectionEnd,
                                                String selectionText, String userFeedback,
                                                List<String> rejectedEntities, Map<String, Object> memoryPack) {
        String original = normalizeNewlines(originalDraft);
        int start = Math.max(0, Math.min(selectionStart, original.length()));
        int end = Math.max(0, Math.min(selectionEnd, original.length()));
        if (end <= start) {
            throw new IllegalArgumentException("选区编辑失败：选区为空或无效");
        }
        if (end - start > 3200) {
            throw new IllegalArgumentException("选区编辑失败：选区过长（建议 ≤3200 字符），请缩小选区后再试");
        }
        String sel = original.substring(start, end);
        // 校验前端传来的 selectionText 是否与实际选区一致
        if (selectionText != null && !selectionText.isBlank()) {
            String provided = normalizeNewlines(selectionText);
            if (!provided.equals(sel)) {
                throw new IllegalArgumentException("选区编辑失败：选区内容已变化（请重新选中后再试）");
            }
        }

        String prefixHint = original.substring(Math.max(0, start - 220), start);
        String suffixHint = original.substring(end, Math.min(original.length(), end + 220));

        List<String> contextItems = new ArrayList<>();
        if (rejectedEntities != null && !rejectedEntities.isEmpty()) {
            contextItems.add("被拒绝概念：" + String.join(", ", rejectedEntities) +
                    "\n【P0-必须】必须删除或彻底改写所有被标记为[拒绝]的概念");
        }
        cardStorage.getStyleCard(projectId).ifPresent(styleCard -> {
            if (styleCard.getStyle() != null && !styleCard.getStyle().isBlank()) {
                contextItems.add("文风要求：\n" + styleCard.getStyle());
            }
        });
        if (memoryPack != null) {
            contextItems.addAll(formatMemoryPackContext(memoryPack));
        }

        String systemPrompt = getSystemPrompt();
        String userPrompt = String.format("""
                ### 选区编辑任务
                
                你只需要改写【选区文本】，输出替换后的选区内容（不含前后文）。
                
                【上文参考（不输出）】
                %s
                
                【选区文本（需改写）】
                <<<SELECTION_START>>>
                %s
                <<<SELECTION_END>>>
                
                【下文参考（不输出）】
                %s
                
                ### 用户指令
                %s
                
                ### 输出要求
                【P0-必须】只输出改写后的选区文本，不含上下文
                【P0-必须】改动必须可见，不得与原选区完全相同
                【P0-必须】保持与上下文的衔接自然
                【P0-必须】禁止附加任何解释或说明
                """, prefixHint, sel, suffixHint, userFeedback);

        List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, contextItems);
        LlmResponse response = callWriterLlm(messages);
        String replacement = normalizeNewlines(response.getContent() != null ? response.getContent() : "").strip();

        if (replacement.isBlank()) {
            throw new IllegalArgumentException("选区编辑失败：模型未生成替换文本（请缩小选区并更具体描述修改）");
        }

        String revised = (original.substring(0, start) + replacement + original.substring(end)).stripTrailing();

        // 如果结果与原文完全相同，重试一次
        if (normalizeForCompare(revised).equals(normalizeForCompare(original))) {
            List<Map<String, String>> retryMessages = new ArrayList<>(messages);
            retryMessages.add(Map.of("role", "assistant", "content", response.getContent() != null ? response.getContent() : ""));
            retryMessages.add(Map.of("role", "user", "content",
                    "你刚才输出的替换文本未产生任何可见修改。\n请重新输出「替换后的选区文本」，并确保：\n- 必须与选区原文不同\n- 严格执行用户反馈\n现在重新输出："));
            LlmResponse response2 = callWriterLlm(retryMessages);
            String replacement2 = normalizeNewlines(response2.getContent() != null ? response2.getContent() : "").strip();
            if (replacement2.isBlank()) {
                throw new IllegalArgumentException("选区编辑失败：模型未生成替换文本（重试后）");
            }
            String revised2 = (original.substring(0, start) + replacement2 + original.substring(end)).stripTrailing();
            if (normalizeForCompare(revised2).equals(normalizeForCompare(original))) {
                throw new IllegalArgumentException("选区编辑失败：未能生成可应用差异（请缩小选区并更具体描述修改）");
            }
            return revised2;
        }
        return revised;
    }

    /** 生成待用户确认的润色候选，不保存草稿或定稿。 */
    public PolishResult rewriteTextDetailed(String projectId, String originalText) {
        return polishService.polish(originalText, deAiRewriteConfigStorage.getConfig(projectId));
    }

    public String rewriteText(String projectId, String originalText) {
        return rewriteTextDetailed(projectId, originalText).rewritten();
    }


    // ========== 工具方法 ==========

    private String normalizeNewlines(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String normalizeForCompare(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private List<String> buildPatchContextItems(String projectId,
                                                List<String> rejectedEntities,
                                                Map<String, Object> memoryPack) {
        List<String> contextItems = new ArrayList<>();
        if (rejectedEntities != null && !rejectedEntities.isEmpty()) {
            contextItems.add("被拒绝概念：" + String.join(", ", rejectedEntities)
                    + "\n【P0-必须】必须删除或彻底改写所有被标记为[拒绝]的概念");
        }
        String styleProjectId = (projectId != null && !projectId.isBlank()) ? projectId : projectIdFromMemory(memoryPack);
        cardStorage.getStyleCard(styleProjectId).ifPresent(styleCard -> {
            if (styleCard.getStyle() != null && !styleCard.getStyle().isBlank()) {
                contextItems.add("文风要求：\n" + styleCard.getStyle());
            }
        });
        if (memoryPack != null) {
            contextItems.addAll(formatMemoryPackContext(memoryPack));
        }
        return contextItems;
    }

    private List<Map<String, String>> buildPatchOpsMessages(String userFeedback,
                                                            String excerpts,
                                                            List<String> contextItems) {
        String critical = """
                ==================================================
                ### 编辑任务（补丁模式）
                ==================================================

                根据【用户反馈】对【原文摘录】提出最小化的局部补丁操作（patch ops）。

                ### 核心约束

                【P0-必须】最小改动：只对必要句段做替换/插入/删除，其他内容保持原样。
                【P0-必须】严禁整稿重写：禁止输出完整正文、禁止大范围改写、禁止无关润色。
                【P0-必须】锚点必须精确：before/anchor 必须是【原文摘录】中出现的原句/片段（逐字匹配）。
                【P0-必须】结尾追加：若用户反馈要求续写/补全/扩写结尾，优先使用 insert_after；可将 anchor 设置为特殊值 %s 表示全文末尾。
                【P0-必须】安全性：replace/delete 必须提供 before；insert_* 必须提供 anchor。
                【P0-必须】中文输出：所有新增 content/after 必须中文。

                ### 输出格式

                【P0-必须】仅输出 JSON（不要代码块/不要解释/不要多余文本）
                【P0-必须】JSON 顶层必须包含 ops 数组
                {
                  "ops": [
                    {
                      "op": "replace" | "delete" | "insert_before" | "insert_after",
                      "before": "原文精确片段",
                      "after": "替换后的新片段",
                      "anchor": "插入锚点",
                      "content": "插入内容",
                      "occurrence": 1,
                      "reason": "可选"
                    }
                  ]
                }
                """.formatted(PATCH_END_ANCHOR);

        String userPrompt = String.join("\n",
                critical,
                "",
                "### 原文摘录（仅供定位与补丁，不要尝试重写整章）",
                "",
                "<<<EXCERPTS_START>>>",
                excerpts == null ? "" : excerpts,
                "<<<EXCERPTS_END>>>",
                "",
                "### 用户反馈（需执行的修改）",
                "",
                "<<<FEEDBACK_START>>>",
                userFeedback == null ? "" : userFeedback,
                "<<<FEEDBACK_END>>>",
                "",
                "### 开始输出",
                "请直接输出 JSON：",
                "",
                "【规则重复】",
                critical
        );
        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }

    private String buildPatchExcerpts(String originalDraft,
                                      String userFeedback,
                                      Map<String, Object> memoryPack,
                                      int headChars,
                                      int tailChars,
                                      int window,
                                      int maxExcerpts) {
        String text = normalizeNewlines(originalDraft);
        if (text.isBlank()) {
            return "";
        }
        String head = text.substring(0, Math.min(headChars, text.length())).trim();
        String tail = text.length() > tailChars ? text.substring(text.length() - tailChars).trim() : text.trim();
        List<String> terms = extractTerms(userFeedback, 10);
        if (memoryPack != null) {
            Object digestObj = memoryPack.get("chapter_digest");
            if (digestObj instanceof Map<?, ?> digest) {
                Object topCharacters = digest.get("top_characters");
                if (topCharacters instanceof List<?> list) {
                    for (Object nameObj : list) {
                        String name = String.valueOf(nameObj == null ? "" : nameObj).trim();
                        if (!name.isBlank() && !terms.contains(name)) {
                            terms.add(name);
                        }
                        if (terms.size() >= 12) {
                            break;
                        }
                    }
                }
            }
            Object snapshotObj = memoryPack.get("card_snapshot");
            if (snapshotObj instanceof Map<?, ?> snapshot) {
                Object characters = snapshot.get("characters");
                if (characters instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> map)) {
                            continue;
                        }
                        Object nameObj = map.get("name");
                        String name = String.valueOf(nameObj == null ? "" : nameObj).trim();
                        if (!name.isBlank() && !terms.contains(name)) {
                            terms.add(name);
                        }
                        if (terms.size() >= 12) {
                            break;
                        }
                    }
                }
            }
        }

        List<int[]> ranges = new ArrayList<>();
        for (String term : terms.stream().limit(10).toList()) {
            int start = 0;
            int hits = 0;
            while (hits < 2) {
                int pos = text.indexOf(term, start);
                if (pos < 0) {
                    break;
                }
                int left = Math.max(0, pos - window);
                int right = Math.min(text.length(), pos + term.length() + window);
                ranges.add(new int[]{left, right});
                start = pos + term.length();
                hits += 1;
            }
        }
        ranges.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            int[] previous = merged.get(merged.size() - 1);
            if (range[0] <= previous[1]) {
                previous[1] = Math.max(previous[1], range[1]);
            } else {
                merged.add(range);
            }
        }

        List<String> blocks = new ArrayList<>();
        if (!head.isBlank()) {
            blocks.add("【开头摘录】\n" + head);
        }
        int picked = 0;
        for (int[] range : merged) {
            if (picked >= maxExcerpts) {
                break;
            }
            String fragment = text.substring(range[0], range[1]).trim();
            if (!fragment.isBlank()) {
                blocks.add("【相关摘录】\n" + fragment);
                picked += 1;
            }
        }
        if (!tail.isBlank()) {
            blocks.add("【结尾摘录】\n" + tail);
        }
        return String.join("\n\n", blocks).trim();
    }

    private String applyPatchResponse(String originalDraft, String rawResponse) throws Exception {
        Map<String, Object> data = parseJsonObject(rawResponse);
        Object opsObj = data.get("ops");
        if (!(opsObj instanceof List<?> ops)) {
            throw new IllegalArgumentException("patch_ops_invalid_schema: missing ops list");
        }
        return applyPatchOps(originalDraft, ops);
    }

    private String applyPatchOps(String originalDraft, List<?> rawOps) {
        String text = normalizeNewlines(originalDraft);
        if (rawOps == null || rawOps.isEmpty()) {
            return text.stripTrailing();
        }
        List<Map<String, Object>> resolved = new ArrayList<>();
        int limit = Math.min(rawOps.size(), 12);
        for (int i = 0; i < limit; i++) {
            Object item = rawOps.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object opObj = map.get("op");
            String op = String.valueOf(opObj == null ? "" : opObj).trim();
            int occurrence = intValue(map.get("occurrence"), 1);
            if ("replace".equals(op) || "delete".equals(op)) {
                Object beforeObj = map.get("before");
                String before = String.valueOf(beforeObj == null ? "" : beforeObj);
                if (before.isBlank()) {
                    continue;
                }
                int pos = findNth(text, before, occurrence);
                if (pos < 0) {
                    throw new IllegalArgumentException("patch_op_failed: before_not_found op#" + (i + 1));
                }
                Map<String, Object> resolvedOp = new LinkedHashMap<>();
                resolvedOp.put("start", pos);
                resolvedOp.put("end", pos + before.length());
                Object afterObj = map.get("after");
                resolvedOp.put("after", "delete".equals(op) ? "" : String.valueOf(afterObj == null ? "" : afterObj));
                resolved.add(resolvedOp);
                continue;
            }
            if ("insert_before".equals(op) || "insert_after".equals(op)) {
                Object anchorObj = map.get("anchor");
                Object contentObj = map.get("content");
                String anchor = String.valueOf(anchorObj == null ? "" : anchorObj);
                String content = String.valueOf(contentObj == null ? "" : contentObj);
                if (anchor.isBlank() || content.isBlank()) {
                    continue;
                }
                int insertAt;
                if (PATCH_END_ANCHOR.equals(anchor)) {
                    insertAt = text.length();
                } else {
                    int pos = findNth(text, anchor, occurrence);
                    if (pos < 0) {
                        throw new IllegalArgumentException("patch_op_failed: anchor_not_found op#" + (i + 1));
                    }
                    insertAt = "insert_before".equals(op) ? pos : pos + anchor.length();
                }
                Map<String, Object> resolvedOp = new LinkedHashMap<>();
                resolvedOp.put("start", insertAt);
                resolvedOp.put("end", insertAt);
                resolvedOp.put("after", content);
                resolved.add(resolvedOp);
            }
        }
        resolved.sort((a, b) -> Integer.compare(
                ((Number) b.get("start")).intValue(),
                ((Number) a.get("start")).intValue()
        ));
        for (Map<String, Object> item : resolved) {
            int start = ((Number) item.get("start")).intValue();
            int end = ((Number) item.get("end")).intValue();
            String after = String.valueOf(item.getOrDefault("after", ""));
            text = text.substring(0, start) + after + text.substring(end);
        }
        return text.stripTrailing();
    }

    private String attemptAutoSelectionFallback(String projectId,
                                                String originalDraft,
                                                String userFeedback,
                                                List<String> rejectedEntities,
                                                Map<String, Object> memoryPack) {
        Map<String, Object> located = autoLocateSelection(originalDraft, userFeedback);
        if (located == null) {
            return null;
        }
        try {
            return suggestRevisionSelectionRange(
                    projectId,
                    originalDraft,
                    intValue(located.get("start"), 0),
                    intValue(located.get("end"), 0),
                    String.valueOf(located.getOrDefault("selection_text", "")),
                    userFeedback,
                    rejectedEntities,
                    memoryPack
            );
        } catch (Exception e) {
            log.info("Auto-selection fallback failed: {}", e.getMessage());
            return null;
        }
    }

    private String attemptExplicitSelectionFallback(String projectId,
                                                    String originalDraft,
                                                    String userFeedback,
                                                    List<String> rejectedEntities,
                                                    Map<String, Object> memoryPack) {
        Map<String, Object> located = locateSelectionByExplicitHints(originalDraft, userFeedback);
        if (located == null) {
            return null;
        }
        try {
            return suggestRevisionSelectionRange(
                    projectId,
                    originalDraft,
                    intValue(located.get("start"), 0),
                    intValue(located.get("end"), 0),
                    String.valueOf(located.getOrDefault("selection_text", "")),
                    userFeedback,
                    rejectedEntities,
                    memoryPack
            );
        } catch (Exception e) {
            log.info("Explicit selection fallback failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> locateSelectionByExplicitHints(String text, String feedback) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String candidate : extractQuotedCandidates(feedback)) {
            int pos = text.indexOf(candidate);
            if (pos >= 0) {
                return Map.of("start", pos, "end", pos + candidate.length(), "selection_text", candidate);
            }
        }
        List<int[]> spans = iterParagraphSpans(text);
        if (spans.isEmpty()) {
            return null;
        }
        String fb = feedback == null ? "" : feedback;
        if (containsAny(fb, "结尾", "最后", "末尾", "收尾", "末段")) {
            int[] span = spans.get(spans.size() - 1);
            return Map.of("start", span[0], "end", span[1], "selection_text", text.substring(span[0], span[1]));
        }
        if (containsAny(fb, "开头", "开篇", "起始", "第一段", "首段")) {
            int[] span = spans.get(0);
            return Map.of("start", span[0], "end", span[1], "selection_text", text.substring(span[0], span[1]));
        }
        Integer paragraphIndex = extractParagraphIndex(fb, spans.size());
        if (paragraphIndex != null && paragraphIndex >= 0 && paragraphIndex < spans.size()) {
            int[] span = spans.get(paragraphIndex);
            return Map.of("start", span[0], "end", span[1], "selection_text", text.substring(span[0], span[1]));
        }
        return null;
    }

    private Map<String, Object> autoLocateSelection(String text, String feedback) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> explicit = locateSelectionByExplicitHints(text, feedback);
        if (explicit != null) {
            return explicit;
        }
        List<int[]> spans = iterParagraphSpans(text);
        if (spans.isEmpty()) {
            return null;
        }

        List<String> terms = extractTerms(feedback, 12);
        if (terms.isEmpty()) {
            return null;
        }
        int[] best = null;
        double bestScore = 0.0;
        for (int[] span : spans) {
            double score = scoreSpan(text.substring(span[0], span[1]), terms);
            if (score > bestScore) {
                bestScore = score;
                best = span;
            }
        }
        if (best == null || bestScore <= 0) {
            return null;
        }
        int start = best[0];
        int end = best[1];
        int maxLen = 1200;
        int minLen = 120;
        if (end - start > maxLen) {
            String segment = text.substring(start, end);
            Integer anchorPos = null;
            for (String term : terms.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                int p = segment.indexOf(term);
                if (p >= 0) {
                    anchorPos = start + p;
                    break;
                }
            }
            if (anchorPos == null) {
                anchorPos = start;
            }
            int winStart = Math.max(start, anchorPos - 260);
            int winEnd = Math.min(end, anchorPos + 940);
            if (winEnd - winStart < minLen) {
                winEnd = Math.min(end, winStart + minLen);
            }
            start = winStart;
            end = winEnd;
        }
        return Map.of("start", start, "end", end, "selection_text", text.substring(start, end));
    }

    private List<String> extractQuotedCandidates(String feedback) {
        List<String> candidates = new ArrayList<>();
        if (feedback == null || feedback.isBlank()) {
            return candidates;
        }
        List<Pattern> patterns = List.of(
                Pattern.compile("[\\\"“](.+?)[\\\"”]"),
                Pattern.compile("[「『](.+?)[」』]"),
                Pattern.compile("[《](.+?)[》]"),
                Pattern.compile("'(.+?)'")
        );
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(feedback);
            while (matcher.find()) {
                String candidate = normalizeNewlines(matcher.group(1)).trim();
                if (!candidate.isBlank() && !candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator.comparingInt(String::length).reversed());
        return candidates;
    }

    private Integer extractParagraphIndex(String feedback, int paragraphCount) {
        if (feedback == null || feedback.isBlank() || paragraphCount <= 0) {
            return null;
        }
        Matcher reverseMatcher = Pattern.compile("倒数第\\s*([\\d一二三四五六七八九十百两零〇]{1,6})\\s*(?:自然)?段").matcher(feedback);
        if (reverseMatcher.find()) {
            Integer ordinal = parseLocalizedOrdinal(reverseMatcher.group(1));
            if (ordinal != null && ordinal > 0 && ordinal <= paragraphCount) {
                return paragraphCount - ordinal;
            }
        }
        Matcher paragraphMatcher = Pattern.compile("第\\s*([\\d一二三四五六七八九十百两零〇]{1,6})\\s*(?:自然)?段").matcher(feedback);
        if (paragraphMatcher.find()) {
            Integer ordinal = parseLocalizedOrdinal(paragraphMatcher.group(1));
            if (ordinal != null && ordinal > 0 && ordinal <= paragraphCount) {
                return ordinal - 1;
            }
        }
        return null;
    }

    private Integer parseLocalizedOrdinal(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim().replace("两", "二").replace("〇", "零");
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        Map<Character, Integer> digits = Map.of(
                '零', 0,
                '一', 1,
                '二', 2,
                '三', 3,
                '四', 4,
                '五', 5,
                '六', 6,
                '七', 7,
                '八', 8,
                '九', 9
        );
        int total = 0;
        int current = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '零') {
                continue;
            }
            Integer digit = digits.get(ch);
            if (digit != null) {
                current = digit;
                continue;
            }
            int unit = switch (ch) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                default -> 0;
            };
            if (unit <= 0) {
                return null;
            }
            total += (current == 0 ? 1 : current) * unit;
            current = 0;
        }
        int value = total + current;
        return value > 0 ? value : null;
    }

    private List<int[]> iterParagraphSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int start = 0;
        int n = text.length();
        while (start < n) {
            while (start < n) {
                char ch = text.charAt(start);
                if (ch != '\n' && ch != '\r' && ch != ' ' && ch != '\t') {
                    break;
                }
                start += 1;
            }
            if (start >= n) {
                break;
            }
            Matcher matcher = Pattern.compile("\\n\\s*\\n").matcher(text);
            if (!matcher.find(start)) {
                spans.add(new int[]{start, n});
                break;
            }
            spans.add(new int[]{start, matcher.start()});
            start = matcher.end();
        }
        return spans;
    }

    private double scoreSpan(String segment, List<String> terms) {
        double score = 0.0;
        for (String term : terms) {
            int count = countOccurrences(segment, term);
            if (count <= 0) {
                continue;
            }
            score += Math.min(4, count) * (1.0 + Math.min(5, term.length()) * 0.15);
        }
        return score;
    }

    private int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return 0;
        }
        int count = 0;
        int start = 0;
        while (true) {
            int pos = haystack.indexOf(needle, start);
            if (pos < 0) {
                return count;
            }
            count += 1;
            start = pos + needle.length();
        }
    }

    private int findNth(String haystack, String needle, int occurrence) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return -1;
        }
        int start = 0;
        int times = Math.max(1, occurrence);
        int pos = -1;
        for (int i = 0; i < times; i++) {
            pos = haystack.indexOf(needle, start);
            if (pos < 0) {
                return -1;
            }
            start = pos + needle.length();
        }
        return pos;
    }

    private List<String> extractTerms(String feedback, int limit) {
        Set<String> stop = Set.of(
                "修改", "润色", "优化", "调整", "提高", "加强", "删除", "添加", "扩写", "续写", "补全",
                "替换", "改成", "改为", "不要", "请", "帮我", "需要", "内容", "文字", "句子", "段落",
                "本章", "章节", "这里", "那里", "更加", "一点"
        );
        List<String> terms = new ArrayList<>();
        if (feedback == null || feedback.isBlank()) {
            return terms;
        }
        Matcher matcher = TERM_PATTERN.matcher(feedback);
        while (matcher.find()) {
            String term = matcher.group().trim();
            if (term.isBlank() || stop.contains(term) || terms.contains(term)) {
                continue;
            }
            terms.add(term);
            if (terms.size() >= limit) {
                break;
            }
        }
        return terms;
    }

    private boolean hasAppendIntent(String feedback) {
        return containsAny(feedback, "续写", "补全", "补充", "扩写", "追加", "接着写", "继续写", "往后写", "延展", "收尾");
    }

    private boolean hasStrongEditIntent(String feedback) {
        return containsAny(feedback, "改为", "改成", "调整为", "调整成", "替换", "修改", "精简", "缩短", "扩写", "补全", "补充", "续写", "完善", "润色");
    }

    private boolean prefersSelectionReplacement(String feedback) {
        return containsAny(feedback, "改为", "改成", "调整为", "调整成", "替换", "修改", "精简", "缩短", "删除", "删掉", "去掉", "润色", "重写");
    }

    private boolean requiresChange(String feedback) {
        return containsAny(feedback, "删除", "删掉", "去掉", "换成", "改成", "改为", "替换", "修改", "增加", "补充", "扩写", "润色", "精简", "缩短");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String attemptAppendOnlyFallback(String originalDraft,
                                             String userFeedback,
                                             List<String> contextItems,
                                             String fallback) {
        String normalized = normalizeNewlines(originalDraft);
        String tailExcerpt = normalized.length() > 1800 ? normalized.substring(normalized.length() - 1800) : normalized;
        String critical = """
                ==================================================
                ### 编辑任务（结尾续写模式）
                ==================================================

                你将为正文只在末尾追加内容，以满足【用户反馈】。

                ### 核心约束

                【P0-必须】只追加：不得改动原文任何已有句子，不得重排原文，不得复述原文。
                【P0-必须】只输出新增内容：不要输出完整正文、不要输出差异说明、不要输出 JSON。
                【P0-必须】必须与结尾衔接自然：承接【结尾摘录】的最后一句，语气与叙事视角保持一致。
                【P0-必须】中文输出：新增内容必须为中文正文。
                """;
        String userPrompt = String.join("\n",
                critical,
                "",
                "### 结尾摘录（用于衔接）",
                "",
                "<<<TAIL_START>>>",
                tailExcerpt,
                "<<<TAIL_END>>>",
                "",
                "### 用户反馈",
                "",
                "<<<FEEDBACK_START>>>",
                userFeedback == null ? "" : userFeedback,
                "<<<FEEDBACK_END>>>",
                "",
                "请直接输出要追加的新段落文本："
        );
        try {
            LlmResponse response = callLlm(buildMessages(getSystemPrompt(), userPrompt, contextItems));
            String appendText = normalizeNewlines(response.getContent() != null ? response.getContent() : "").trim();
            if (appendText.isBlank()) {
                return fallback;
            }
            String base = normalized.stripTrailing();
            String separator = base.isEmpty() || base.endsWith("\n") ? "\n" : "\n\n";
            return (base + separator + appendText).stripTrailing();
        } catch (Exception e) {
            return fallback;
        }
    }
    
    private List<Map<String, String>> buildRevisionMessages(String projectId, String originalDraft, String userFeedback,
                                                             List<String> rejectedEntities,
                                                             Map<String, Object> memoryPack) {
        List<String> contextItems = new ArrayList<>();

        // 原始草稿
        contextItems.add("<<<DRAFT_START>>>\n" + originalDraft + "\n<<<DRAFT_END>>>");

        // 被否决的设定
        if (rejectedEntities != null && !rejectedEntities.isEmpty()) {
            contextItems.add("【P0-必须】被拒绝概念处理：必须删除或彻底改写所有被标记为[拒绝]的概念，确保最终稿中完全不出现。\n被否决的设定：\n- " +
                    String.join("\n- ", rejectedEntities));
        }

        String styleProjectId = (projectId != null && !projectId.isBlank()) ? projectId : projectIdFromMemory(memoryPack);
        cardStorage.getStyleCard(styleProjectId).ifPresent(styleCard -> {
            if (styleCard.getStyle() != null && !styleCard.getStyle().isBlank()) {
                contextItems.add("文风要求：\n" + styleCard.getStyle());
            }
        });
        if (memoryPack != null) {
            contextItems.addAll(formatMemoryPackContext(memoryPack));
        }

        String critical = String.format("""
                ==================================================
                ### 修订任务
                ==================================================
                
                根据【用户反馈】修订【原稿】
                
                ### 执行规则
                
                【P0-必须】完整执行：
                  - 用户的每一条修改意见都必须执行
                  - 改动必须可见、可验证
                
                【P0-必须】极简修改（Minimal Edits）：
                  - 仅替换/插入真正需要修改的句子，不重写边缘上下文
                  - 原文的留白、短句短段必须保持原样，绝不可擅自合并段落
                
                【P0-必须】最小改动：
                  - 未被提及的内容必须保持不变
                  - 禁止无故换词、调整语序、改标点、改分段
                  - 绝对禁止随意将原稿的多个短段落合并成一个大段落！保持网文短句排版
                  - 禁止「顺手润色」
                
                【P0-必须】信息保真：
                  - 禁止新增设定/剧情/人物
                  - 禁止引入与原稿矛盾的事实
                
                【P0-必须】风格一致：
                  - 保持原文文风与语气
                  - 保持专名/称谓一致
                
                ### 输出要求
                
                【P0-必须】仅输出修改后的完整正文（中文）
                【P0-必须】禁止添加解释、说明、修改记录
                【P0-必须】输出末尾必须以单独一行结束标记收尾：<<<REVISED_DRAFT_END>>>
                【P0-必须】标记之后不得再输出任何字符
                """);

        String userPrompt = String.format("""
                %s
                
                ### 用户反馈（需执行的修改）
                
                <<<FEEDBACK_START>>>
                %s
                <<<FEEDBACK_END>>>
                
                ### 开始输出
                请直接输出修改后的完整正文，并在最后一行输出结束标记：
                <<<REVISED_DRAFT_END>>>
                
                ────────────────────────────────────────
                【修订规则重复】
                %s
                """, critical, userFeedback != null ? userFeedback : "", critical);

        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }

    private String projectIdFromMemory(Map<String, Object> memoryPack) {
        if (memoryPack == null) {
            return "";
        }
        return String.valueOf(memoryPack.getOrDefault("project_id", ""));
    }
    
    private String extractRevisedContent(String rawResponse) {
        if (rawResponse == null) return "";

        // 移除 <think> 标签
        String content = THINK_PATTERN.matcher(rawResponse).replaceAll("");

        // 截断结束标记（对齐 Python EDITOR_REVISION_END_MARKER）
        int markerIdx = content.indexOf("<<<REVISED_DRAFT_END>>>");
        if (markerIdx >= 0) {
            content = content.substring(0, markerIdx);
        }

        // 提取 <revised> 或 <draft> 标签内容
        for (String tag : List.of("revised", "draft", "content")) {
            int start = content.indexOf("<" + tag + ">");
            int end = content.indexOf("</" + tag + ">");

            if (start != -1) {
                start += tag.length() + 2;
                if (end == -1) end = content.length();
                return content.substring(start, end).trim();
            }
        }

        return content.trim();
    }
    
    private int extractVersionNumber(String version) {
        if (version == null || version.equals("current")) {
            return 1;
        }
        
        try {
            return Integer.parseInt(version.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String attemptQuotedSelectionFallback(String projectId, String originalDraft, String userFeedback,
                                                  List<String> rejectedEntities, Map<String, Object> memoryPack) {
        String selection = extractQuotedSelection(userFeedback, originalDraft);
        if (selection == null || selection.isBlank()) {
            throw new IllegalArgumentException("未能生成可应用的差异修改：请在指令中复制粘贴要修改的原句/段落，或直接说“改第二段 / 改开头 / 改结尾”，也可使用选区编辑进行精确定位。");
        }
        return suggestRevisionSelection(projectId, originalDraft, selection, userFeedback, rejectedEntities, memoryPack);
    }

    private String extractQuotedSelection(String feedback, String originalDraft) {
        if (feedback == null || feedback.isBlank() || originalDraft == null || originalDraft.isBlank()) {
            return null;
        }
        for (String candidate : extractQuotedCandidates(feedback)) {
            if (originalDraft.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }


    private Map<String, Object> parseJsonObject(String raw) throws Exception {
        String content = THINK_PATTERN.matcher(raw == null ? "" : raw).replaceAll("").trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    private List<String> formatMemoryPackContext(Map<String, Object> memoryPack) {
        List<String> contextItems = new ArrayList<>();
        if (memoryPack == null || memoryPack.isEmpty()) {
            return contextItems;
        }
        Object payloadObj = memoryPack.get("payload");
        if (!(payloadObj instanceof Map<?, ?>) || ((Map<?, ?>) payloadObj).isEmpty()) {
            payloadObj = memoryPack.get("working_memory_payload");
        }
        if (!(payloadObj instanceof Map<?, ?>) || ((Map<?, ?>) payloadObj).isEmpty()) {
            boolean looksLikePayload = memoryPack.containsKey("working_memory")
                    || memoryPack.containsKey("evidence_pack")
                    || memoryPack.containsKey("unresolved_gaps");
            if (looksLikePayload) {
                payloadObj = memoryPack;
            }
        }
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return contextItems;
        }
        Object digestObj = memoryPack.get("chapter_digest");
        if (digestObj instanceof Map<?, ?> digest) {
            List<String> parts = new ArrayList<>();
            Object summary = digest.get("summary");
            if (summary != null && !summary.toString().isBlank()) {
                parts.add("摘要：" + summary);
            }
            Object topCharacters = digest.get("top_characters");
            if (topCharacters instanceof List<?> list && !list.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object item : list) {
                    String text = item != null ? String.valueOf(item).trim() : "";
                    if (!text.isBlank()) {
                        names.add(text);
                    }
                    if (names.size() >= 8) {
                        break;
                    }
                }
                if (!names.isEmpty()) {
                    parts.add("人物：" + String.join("、", names));
                }
            }
            Object topWorld = digest.get("top_world");
            if (topWorld instanceof List<?> list && !list.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object item : list) {
                    String text = item != null ? String.valueOf(item).trim() : "";
                    if (!text.isBlank()) {
                        names.add(text);
                    }
                    if (names.size() >= 8) {
                        break;
                    }
                }
                if (!names.isEmpty()) {
                    parts.add("设定：" + String.join("、", names));
                }
            }
            Object tail = digest.get("tail_excerpt");
            if (tail != null && !tail.toString().isBlank()) {
                String tailText = tail.toString();
                parts.add("结尾片段（用于续写对齐）：\n" + (tailText.length() > 900 ? tailText.substring(0, 900) : tailText));
            }
            if (!parts.isEmpty()) {
                contextItems.add("本章内容概览（压缩自本章正文）：\n" + String.join("\n", parts));
            }
        }

        Object workingMemory = payload.get("working_memory");
        if (workingMemory != null && !workingMemory.toString().isBlank()) {
            contextItems.add("工作记忆：\n" + workingMemory);
        } else {
            Object evidencePack = payload.get("evidence_pack");
            if (evidencePack instanceof Map<?, ?> pack) {
                Object itemsObj = pack.get("items");
                if (itemsObj instanceof List<?> items && !items.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ordered = new ArrayList<>((Collection<? extends Map<String, Object>>) (List<?>) items);
                    ordered.sort((a, b) -> Double.compare(doubleValue(b.get("score")), doubleValue(a.get("score"))));
                    StringBuilder sb = new StringBuilder("证据摘录：\n");
                    int count = 0;
                    for (Map<String, Object> ev : ordered) {
                        String itemType = String.valueOf(ev.getOrDefault("type", "evidence"));
                        String text = String.valueOf(ev.getOrDefault("text", "")).trim();
                        if (text.isBlank()) {
                            continue;
                        }
                        List<String> sourceParts = new ArrayList<>();
                        Object sourceObj = ev.get("source");
                        if (sourceObj instanceof Map<?, ?> source) {
                            for (String key : List.of("chapter", "draft", "path", "field", "fact_id", "card", "introduced_in")) {
                                String value = stringValue(source, key, "").trim();
                                if (!value.isBlank()) {
                                    sourceParts.add(value);
                                }
                            }
                        }
                        String line = "[" + itemType + "] " + text;
                        if (!sourceParts.isEmpty()) {
                            line += " (" + String.join(" / ", sourceParts) + ")";
                        }
                        sb.append(line).append("\n");
                        if (++count >= 6) break;
                    }
                    if (count > 0) {
                        contextItems.add(sb.toString());
                    }
                }
            }
            Object unresolved = payload.get("unresolved_gaps");
            if (unresolved instanceof List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder("待确认缺口：\n");
                int count = 0;
                for (Object item : list) {
                    String text = "";
                    if (item instanceof Map<?, ?> gap) {
                        text = stringValue(gap, "text", "");
                    } else if (item != null) {
                        text = String.valueOf(item).trim();
                    }
                    if (!text.isBlank()) {
                        sb.append("- ").append(text).append("\n");
                        if (++count >= 6) break;
                    }
                }
                if (count > 0) {
                    contextItems.add(sb.toString());
                }
            }
        }
        Object snapshotObj = memoryPack.get("card_snapshot");
        if (snapshotObj instanceof Map<?, ?> snapshot) {
            contextItems.addAll(formatCardSnapshot(snapshot));
        }
        return contextItems;
    }

    private List<String> formatCardSnapshot(Map<?, ?> snapshot) {
        List<String> contextItems = new ArrayList<>();
        Object charsObj = snapshot.get("characters");
        if (charsObj instanceof List<?> characters && !characters.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Object item : characters) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = stringValue(map, "name", "").trim();
                if (name.isBlank()) {
                    continue;
                }
                String stars = stringValue(map, "stars", "").trim();
                String starLabel = stars.isBlank() ? "" : "★" + stars;
                List<String> parts = new ArrayList<>();
                String identity = stringValue(map, "identity", "").trim();
                if (!identity.isBlank()) {
                    parts.add("身份：" + identity);
                }
                String appearance = stringValue(map, "appearance", "").trim();
                if (!appearance.isBlank()) {
                    parts.add("外貌：" + appearance);
                }
                Object aliasesObj = map.get("aliases");
                if (aliasesObj instanceof List<?> aliases && !aliases.isEmpty()) {
                    List<String> aliasTexts = new ArrayList<>();
                    for (Object alias : aliases) {
                        String text = alias != null ? String.valueOf(alias).trim() : "";
                        if (!text.isBlank()) {
                            aliasTexts.add(text);
                        }
                        if (aliasTexts.size() >= 4) {
                            break;
                        }
                    }
                    if (!aliasTexts.isEmpty()) {
                        parts.add("别名：" + String.join("、", aliasTexts));
                    }
                }
                String line = "- " + name + starLabel;
                if (!parts.isEmpty()) {
                    line += "（" + String.join("；", parts) + "）";
                }
                lines.add(line);
                if (lines.size() >= 8) break;
            }
            if (!lines.isEmpty()) {
                contextItems.add("角色设定（快照）：\n" + String.join("\n", lines));
            }
        }
        Object worldObj = snapshot.get("world");
        if (worldObj instanceof List<?> worlds && !worlds.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Object item : worlds) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = stringValue(map, "name", "").trim();
                if (name.isBlank()) {
                    continue;
                }
                String category = stringValue(map, "category", "").trim();
                String summary = stringValue(map, "summary", "").trim();
                List<String> parts = new ArrayList<>();
                if (!category.isBlank()) {
                    parts.add("类别：" + category);
                }
                if (!summary.isBlank()) {
                    parts.add(summary);
                }
                String line = "- " + name;
                if (!parts.isEmpty()) {
                    line += "（" + String.join("；", parts) + "）";
                }
                lines.add(line);
                if (lines.size() >= 8) break;
            }
            if (!lines.isEmpty()) {
                contextItems.add("设定卡（快照）：\n" + String.join("\n", lines));
            }
        }
        Object styleObj = snapshot.get("style");
        if (styleObj != null && !String.valueOf(styleObj).isBlank()) {
            contextItems.add("文风参考（快照）：\n" + styleObj);
        }
        return contextItems;
    }

    private String stringValue(Map<?, ?> map, String key, String fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private LlmResponse callWriterLlm(List<Map<String, String>> messages) {
        String providerId = llmGateway.getProviderForAgent("writer");
        return llmGateway.chat(messages, providerId, null, null);
    }
}
