package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.media.Storage;
import com.videostudio.repository.ReportRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 报告导出：Markdown 原文 + Word 文档下载 */
@RestController
@RequestMapping("/api/jobs/{id}/report")
public class ReportController {

    private final ReportRepository reportRepository;
    private final Storage storage;

    public ReportController(ReportRepository reportRepository, Storage storage) {
        this.reportRepository = reportRepository;
        this.storage = storage;
    }

    @GetMapping(value = "/markdown", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable long id) {
        String markdown = reportRepository.findByJob(id)
                .orElseThrow(() -> ApiException.notFound("该任务还没有生成报告"))
                .markdown();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=report.md")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(markdown);
    }

    @GetMapping("/docx")
    public ResponseEntity<Resource> docx(@PathVariable long id) {
        var report = reportRepository.findByJob(id)
                .orElseThrow(() -> ApiException.notFound("该任务还没有生成报告"));
        String path = report.docxPath();
        if (path == null || path.isBlank()) {
            path = storage.reportDocx(report.assetId(), id).toString();
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw ApiException.notFound("报告文件不存在，请重新生成");
        }
        String name = "video-studio-report-" + id + ".docx";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(new FileSystemResource(file));
    }
}
