package com.moxu.controller;

import com.moxu.model.entity.Project;
import com.moxu.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 项目管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    
    private final ProjectStorage projectStorage;
    
    @GetMapping("")
    public List<Project> listProjects() {
        return projectStorage.listProjects();
    }
    
    @PostMapping("")
    public Project createProject(@RequestBody ProjectCreateRequest request) {
        try {
            return projectStorage.createProject(
                    request.name(),
                    request.description(),
                    request.language()
            );
        } catch (IOException e) {
            log.error("创建项目失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @GetMapping("/{projectId}")
    public Project getProject(@PathVariable String projectId) {
        return projectStorage.getProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }
    
    @PatchMapping("/{projectId}")
    public Map<String, Object> renameProject(@PathVariable String projectId, 
                                              @RequestBody ProjectRenameRequest request) {
        try {
            Project project = projectStorage.updateProject(projectId, request.name(), null);
            return Map.of(
                    "success", true,
                    "project", project
            );
        } catch (IOException e) {
            log.error("重命名项目失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @DeleteMapping("/{projectId}")
    public Map<String, Object> deleteProject(@PathVariable String projectId) {
        boolean success = projectStorage.deleteProject(projectId);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return Map.of(
                "success", true,
                "message", "Project deleted"
        );
    }
    
    public record ProjectCreateRequest(String name, String description, String language) {}
    public record ProjectRenameRequest(String name) {}
}
