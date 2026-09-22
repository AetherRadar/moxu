package com.wenshape.controller;

import com.wenshape.storage.MemoryPackStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 记忆包控制器 - 章节记忆包状态
 */
@RestController
@RequiredArgsConstructor
public class MemoryPackController {
    
    private final MemoryPackStorage memoryPackStorage;
    
    /**
     * 获取章节记忆包状态
     */
    @GetMapping("/projects/{projectId}/memory-pack/{chapter}")
    public Map<String, Object> getMemoryPackStatus(
            @PathVariable String projectId,
            @PathVariable String chapter) {
        
        Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
        return memoryPackStorage.buildStatus(chapter, pack);
    }
}
