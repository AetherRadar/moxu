package com.moxu.controller;

import com.moxu.dto.BookImportTextRequest;
import com.moxu.dto.BookImportConfirmRequest;
import com.moxu.service.BookImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * 书籍导入接口：支持 txt/epub 导入为普通项目章节，并执行快速初始化链。
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/imports")
@RequiredArgsConstructor
public class BookImportController {

    private final BookImportService bookImportService;

    @PostMapping(value = "/book/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> previewBook(@PathVariable String projectId,
                                           @RequestPart("file") MultipartFile file) {
        try {
            validateFile(file);
            return bookImportService.preview(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            log.error("预览导入书籍失败: projectId={}", projectId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping(value = "/book/text/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> previewBookText(@PathVariable String projectId,
                                               @RequestBody BookImportTextRequest request) {
        try {
            validateTextRequest(request);
            return bookImportService.previewText(request.getSourceName(), request.getText());
        } catch (IOException e) {
            log.error("预览粘贴文本导入失败: projectId={}", projectId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping(value = "/book/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> confirmBookImport(@PathVariable String projectId,
                                                 @RequestBody BookImportConfirmRequest request) {
        try {
            validateConfirmRequest(request);
            return bookImportService.importPreparedBook(projectId, request);
        } catch (IOException e) {
            log.error("确认导入书籍失败: projectId={}", projectId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!(name.endsWith(".txt") || name.endsWith(".epub"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 txt 或 epub 文件");
        }
    }

    private void validateTextRequest(BookImportTextRequest request) {
        if (request == null || request.getText() == null || request.getText().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
    }

    private void validateConfirmRequest(BookImportConfirmRequest request) {
        if (request == null || request.getVolumes() == null || request.getVolumes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "volumes is required");
        }
        boolean hasSelected = request.getVolumes().stream()
                .filter(volume -> volume != null && volume.getChapters() != null)
                .flatMap(volume -> volume.getChapters().stream())
                .anyMatch(chapter -> chapter != null && (chapter.getSelected() == null || chapter.getSelected()));
        if (!hasSelected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个章节导入");
        }
    }
}
