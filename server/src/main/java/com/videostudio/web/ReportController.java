package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.media.Storage;
import com.videostudio.report.SubtitleWriter;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 报告导出：Markdown 原文 + Word 文档 + 字幕文件 */
@RestController
@RequestMapping("/api/jobs/{id}/report")
public class ReportController {

    private final ReportRepository reportRepository;
    private final TranscriptRepository transcriptRepository;
    private final JobRepository jobRepository;
    private final Storage storage;

    public ReportController(ReportRepository reportRepository, TranscriptRepository transcriptRepository,
                            JobRepository jobRepository, Storage storage) {
        this.reportRepository = reportRepository;
        this.transcriptRepository = transcriptRepository;
        this.jobRepository = jobRepository;
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

    /**
     * 字幕导出。format 支持 srt / vtt / txt，默认 srt。
     * <p>字幕直接来自 transcript_segment，因此和报告里的时间戳完全同源；
     * 用户把导出的字幕拖进播放器，就能和界面上的跳转对齐。
     */
    @GetMapping("/subtitle")
    public ResponseEntity<String> subtitle(@PathVariable long id,
                                           @RequestParam(name = "format", defaultValue = "srt") String format) {
        var job = jobRepository.find(id).orElseThrow(() -> ApiException.notFound("任务不存在: " + id));
        String normalized = format == null ? "srt" : format.strip().toLowerCase();
        if (!normalized.equals("srt") && !normalized.equals("vtt")
                && !normalized.equals("txt") && !normalized.equals("text")) {
            throw new ApiException("不支持的字幕格式: " + format + "（可选 srt / vtt / txt）");
        }
        List<TranscriptSegment> segments = transcriptRepository.listByJob(id);
        if (segments.isEmpty()) {
            throw ApiException.notFound("该任务还没有可导出的转写内容");
        }
        String body = switch (SubtitleWriter.extension(normalized)) {
            case "vtt" -> SubtitleWriter.vtt(segments);
            case "txt" -> SubtitleWriter.plainText(segments);
            default -> SubtitleWriter.srt(segments);
        };
        String name = "video-studio-" + job.id() + "." + SubtitleWriter.extension(normalized);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(SubtitleWriter.contentType(normalized)))
                .body(body);
    }
}
