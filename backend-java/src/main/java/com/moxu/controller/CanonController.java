package com.moxu.controller;

import com.moxu.model.entity.Fact;
import com.moxu.storage.CanonStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 事实表管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/canon")
@RequiredArgsConstructor
public class CanonController {
    
    private final CanonStorage canonStorage;
    
    @GetMapping("/facts")
    public List<Fact> listFacts(@PathVariable String projectId) {
        return canonStorage.listFacts(projectId);
    }

    @PostMapping("/facts")
    public Fact createFact(@PathVariable String projectId, @RequestBody Fact fact) {
        try {
            return canonStorage.saveFact(projectId, fact);
        } catch (IOException e) {
            log.error("创建事实失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // factId 可能含非 F\d+ 字符（如手动创建的 slug），统一走 by-id 前缀避免路径段歧义
    @PutMapping("/facts/by-id/{factId}")
    public Fact updateFactById(@PathVariable String projectId, @PathVariable String factId, @RequestBody Fact fact) {
        if (!canonStorage.getFact(projectId, factId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fact not found");
        }

        try {
            fact.setId(factId);
            return canonStorage.saveFact(projectId, fact);
        } catch (IOException e) {
            log.error("更新事实失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/facts/by-id/{factId}")
    public Map<String, Object> deleteFactById(@PathVariable String projectId, @PathVariable String factId) {
        boolean success = canonStorage.deleteFact(projectId, factId);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fact not found");
        }
        return Map.of("success", true, "message", "Fact deleted");
    }

    @GetMapping("/timeline")
    public List<Map<String, Object>> listTimeline(@PathVariable String projectId,
                                                  @RequestParam(required = false) String chapter) {
        if (chapter != null && !chapter.isBlank()) {
            return canonStorage.listTimelineEventsByChapter(projectId, chapter);
        }
        return canonStorage.listTimelineEvents(projectId);
    }

    @PostMapping("/timeline")
    public Map<String, Object> createTimelineEvent(@PathVariable String projectId,
                                                   @RequestBody Map<String, Object> event) {
        try {
            canonStorage.saveTimelineEvent(projectId, event);
            return Map.of("success", true, "message", "Timeline event added");
        } catch (IOException e) {
            log.error("保存时间线事件失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 编辑事件：id 走路径，body 里的 id 会被忽略。 */
    @PutMapping("/timeline/{eventId}")
    public Map<String, Object> updateTimelineEvent(@PathVariable String projectId,
                                                   @PathVariable String eventId,
                                                   @RequestBody Map<String, Object> event) {
        try {
            canonStorage.updateTimelineEvent(projectId, eventId, event);
            return Map.of("success", true, "message", "Timeline event updated");
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Timeline event not found");
        } catch (IOException e) {
            log.error("更新时间线事件失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/timeline/{eventId}")
    public Map<String, Object> deleteTimelineEvent(@PathVariable String projectId,
                                                   @PathVariable String eventId) {
        if (!canonStorage.deleteTimelineEvent(projectId, eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Timeline event not found");
        }
        return Map.of("success", true, "message", "Timeline event deleted");
    }

    @GetMapping("/character-state")
    public List<Map<String, Object>> listCharacterState(@PathVariable String projectId) {
        return canonStorage.listCharacterStates(projectId);
    }

    @PutMapping("/character-state/{characterName}")
    public Map<String, Object> saveCharacterState(@PathVariable String projectId,
                                                  @PathVariable String characterName,
                                                  @RequestBody Map<String, Object> state) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(state);
        payload.putIfAbsent("character", characterName);
        try {
            canonStorage.saveCharacterState(projectId, payload);
            return payload;
        } catch (IOException e) {
            log.error("保存角色状态失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
