package com.videostudio.service;

import com.videostudio.agent.AgentModels;
import com.videostudio.agent.AnalysisAgent;
import com.videostudio.agent.EvidenceValidator;
import com.videostudio.agent.ReportComposer;
import com.videostudio.asr.TranscriptionService;
import com.videostudio.common.Json;
import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.config.AgentProperties;
import com.videostudio.config.AiProperties;
import com.videostudio.context.ChunkBuilder;
import com.videostudio.context.ChunkEnricher;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.AnalysisReport;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.JobEvent;
import com.videostudio.domain.JobStage;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.MediaKind;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.media.AudioExtractor;
import com.videostudio.media.FrameExtractor;
import com.videostudio.media.MediaInfo;
import com.videostudio.media.MediaProbe;
import com.videostudio.media.Storage;
import com.videostudio.ocr.VisualEvidenceService;
import com.videostudio.report.DocxExporter;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.MediaRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 分析流水线：探针 → 音频/转写 ∥ 关键帧/OCR → 分块索引 → Agent → 报告落库。
 * <p>每个阶段都会推进进度并写一条 SSE 事件，前端因此可以实时看到「现在在做什么」。
 */
@Service
public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);

    private final MediaRepository mediaRepository;
    private final JobRepository jobRepository;
    private final JobEventRepository eventRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualRepository visualRepository;
    private final ChunkRepository chunkRepository;
    private final ReportRepository reportRepository;

    private final MediaProbe probe;
    private final AudioExtractor audioExtractor;
    private final FrameExtractor frameExtractor;
    private final TranscriptionService transcriptionService;
    private final VisualEvidenceService visualService;
    private final ChunkEnricher chunkEnricher;
    private final AnalysisAgent agent;
    private final DocxExporter docxExporter;
    private final Storage storage;
    private final AgentProperties agentProps;
    private final AiProperties aiProps;

    public AnalysisPipeline(MediaRepository mediaRepository, JobRepository jobRepository,
                            JobEventRepository eventRepository, TranscriptRepository transcriptRepository,
                            VisualRepository visualRepository, ChunkRepository chunkRepository,
                            ReportRepository reportRepository, MediaProbe probe, AudioExtractor audioExtractor,
                            FrameExtractor frameExtractor, TranscriptionService transcriptionService,
                            VisualEvidenceService visualService, ChunkEnricher chunkEnricher,
                            AnalysisAgent agent, DocxExporter docxExporter, Storage storage,
                            AgentProperties agentProps, AiProperties aiProps) {
        this.mediaRepository = mediaRepository;
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualRepository = visualRepository;
        this.chunkRepository = chunkRepository;
        this.reportRepository = reportRepository;
        this.probe = probe;
        this.audioExtractor = audioExtractor;
        this.frameExtractor = frameExtractor;
        this.transcriptionService = transcriptionService;
        this.visualService = visualService;
        this.chunkEnricher = chunkEnricher;
        this.agent = agent;
        this.docxExporter = docxExporter;
        this.storage = storage;
        this.agentProps = agentProps;
        this.aiProps = aiProps;
    }

    /** 单个任务执行过程中的上下文，避免在方法间传递一长串参数 */
    private final class Run {
        final long jobId;
        final MediaAsset asset;
        final AnalysisJob job;
        final Path source;
        final AtomicLong seq;
        final List<String> warnings = new ArrayList<>();
        JobStage current = JobStage.QUEUED;
        long durationMs;

        Run(AnalysisJob job, MediaAsset asset, Path source) {
            this.job = job;
            this.jobId = job.id();
            this.asset = asset;
            this.source = source;
            this.seq = eventRepository.sequencer(job.id());
            this.durationMs = asset.durationMs();
        }

        Consumer<String> progress(JobStage stage) {
            return message -> {
                int floor = stage.floor();
                jobRepository.updateProgress(jobId, stage, floor);
                emit(stage, JobEvent.TYPE_LOG, "info", floor, message);
            };
        }

        void emit(JobStage stage, String type, String level, int progress, String message) {
            JobEvent event = new JobEvent(0, stage, type, level, progress, message, null, java.time.Instant.now());
            eventRepository.append(jobId, seq.incrementAndGet(), event);
        }

        void stage(JobStage stage, String message) {
            current = stage;
            jobRepository.updateProgress(jobId, stage, stage.floor());
            emit(stage, JobEvent.TYPE_STAGE, "info", stage.floor(), message);
        }

        void warn(String message) {
            warnings.add(message);
            jobRepository.addNotice(jobId, message);
            emit(current, JobEvent.TYPE_WARNING, "warn", 0, message);
        }
    }

    public void run(long jobId) {
        AnalysisJob job = jobRepository.find(jobId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + jobId));
        MediaAsset asset = mediaRepository.find(job.assetId())
                .orElseThrow(() -> new IllegalStateException("媒体不存在: " + job.assetId()));
        Path source = Path.of(asset.storagePath());
        Run run = new Run(job, asset, source);
        try {
            jobRepository.markRunning(jobId);
            execute(run, job, asset, source);
            jobRepository.markSucceeded(jobId);
            run.emit(JobStage.DONE, JobEvent.TYPE_DONE, "info", 100, "分析完成");
        } catch (Exception e) {
            log.error("任务 {} 失败", jobId, e);
            String message = Texts.oneLine(e.getMessage() == null ? e.toString() : e.getMessage(), 300);
            jobRepository.markFailed(jobId, message);
            run.emit(JobStage.FAILED, JobEvent.TYPE_ERROR, "error", 100, "分析失败：" + message);
        }
    }

    private void execute(Run run, AnalysisJob job, MediaAsset asset, Path source) throws Exception {
        run.stage(JobStage.PROBE, "解析媒体信息");
        if (!java.nio.file.Files.isReadable(source)) {
            throw new IllegalStateException("源文件不存在或不可读: " + source);
        }
        MediaInfo info = probe.probe(source.toString());
        run.durationMs = info.durationMs() > 0 ? info.durationMs() : run.durationMs;
        run.emit(JobStage.PROBE, JobEvent.TYPE_METRIC, "info", JobStage.PROBE.floor(),
                "时长 %s，分辨率 %s".formatted(Times.human(run.durationMs),
                        info.width() == null ? "音频" : info.width() + "x" + info.height()));

        // ---- 语音：抽音频 → 切片 → ASR
        List<TranscriptSegment> transcripts = List.of();
        String asrEngine = "none";
        if (info.hasAudio()) {
            run.stage(JobStage.AUDIO, "抽取音频轨");
            Path wav = audioExtractor.extract(source, storage.audioFile(asset.id(), run.jobId));
            run.stage(JobStage.TRANSCRIBE, "语音识别中");
            TranscriptionService.Outcome outcome = transcriptionService.transcribe(wav, storage.asrDir(asset.id(), run.jobId),
                    run.durationMs, run.progress(JobStage.TRANSCRIBE));
            transcripts = outcome.segments();
            asrEngine = outcome.engine();
            outcome.warnings().forEach(run::warn);
            transcriptRepository.replace(asset.id(), run.jobId, transcripts);
            run.emit(JobStage.TRANSCRIBE, JobEvent.TYPE_METRIC, "info", JobStage.TRANSCRIBE.floor(),
                    "转写 %d 段（引擎 %s）".formatted(transcripts.size(), asrEngine));
        } else {
            run.warn("该文件没有音频轨，跳过语音识别");
        }

        // ---- 画面：关键帧 → OCR
        List<VisualEvidence> visuals = List.of();
        if (info.hasVideo() && asset.kind() == MediaKind.VIDEO) {
            run.stage(JobStage.FRAMES, "抽取关键帧");
            List<FrameExtractor.Frame> frames = frameExtractor.extract(source, storage.frameDir(asset.id(), run.jobId), run.durationMs);
            run.emit(JobStage.FRAMES, JobEvent.TYPE_METRIC, "info", JobStage.FRAMES.floor(),
                    "抽取关键帧 %d 张".formatted(frames.size()));
            if (!frames.isEmpty()) {
                run.stage(JobStage.OCR, "识别画面文字");
                VisualEvidenceService.Outcome ocr = visualService.extract(frames, run.progress(JobStage.OCR));
                visuals = ocr.items();
                visualRepository.replace(asset.id(), run.jobId, visuals);
                run.emit(JobStage.OCR, JobEvent.TYPE_METRIC, "info", JobStage.OCR.floor(),
                        "画面文字 %d 条（去重丢弃 %d 帧）".formatted(visuals.size(), ocr.dropped()));
            }
        }

        if (transcripts.isEmpty() && visuals.isEmpty()) {
            throw new IllegalStateException("未能从该媒体中获得任何可用证据（语音与画面文字均为空），请检查文件内容或本地工具配置");
        }

        // ---- 分块与索引
        run.stage(JobStage.SEGMENT, "对齐时间轴");
        run.stage(JobStage.CHUNK, "构建上下文分块");
        List<ContextChunk> chunks = ChunkBuilder.build(transcripts, visuals, run.durationMs, agentProps);
        chunks = chunkEnricher.enrich(chunks, run.progress(JobStage.CHUNK));
        chunkRepository.replace(asset.id(), run.jobId, chunks, aiProps.getEmbeddingModel());
        run.emit(JobStage.CHUNK, JobEvent.TYPE_METRIC, "info", JobStage.CHUNK.floor(),
                "生成 %d 个上下文分块".formatted(chunks.size()));

        // ---- Agent
        run.stage(JobStage.RETRIEVE, "检索相关证据");
        AnalysisAgent.Request request = new AnalysisAgent.Request(run.jobId, job.focus(), job.mode(),
                transcripts, visuals, chunks, Times.human(run.durationMs));
        run.stage(JobStage.PLAN, "规划分析提纲");
        AnalysisAgent.Outcome outcome = agent.run(request, message ->
                run.emit(JobStage.EXECUTE, JobEvent.TYPE_LOG, "info", JobStage.EXECUTE.floor(), message));
        run.emit(JobStage.CRITIC, JobEvent.TYPE_METRIC, "info", JobStage.CRITIC.floor(),
                "证据校验：" + (outcome.critique().passed() ? "通过" : "存在未通过项")
                        + "（%d 轮）".formatted(outcome.rounds()));

        // ---- 文档
        run.stage(JobStage.REPORT, "生成分析报告");
        AgentModels.Draft draft = outcome.draft();
        List<String> citations = ReportComposer.citations(draft);
        boolean verified = outcome.critique().passed();
        String markdown = ReportComposer.markdown(
                new ReportComposer.Meta(job.focus(), outcome.mode(), Times.human(run.durationMs),
                        describeEngine(asrEngine, visuals.size()), outcome.modeReason(), outcome.rounds(), verified),
                draft, outcome.critique(), citations);

        Path docx = storage.reportDocx(asset.id(), run.jobId);
        try {
            docxExporter.export(markdown, docx);
        } catch (Exception e) {
            run.warn("生成 Word 文档失败：" + Texts.oneLine(e.getMessage(), 120));
            docx = null;
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("engine", outcome.engine());
        trace.put("asrEngine", asrEngine);
        trace.put("rounds", outcome.rounds());
        trace.put("verified", verified);
        trace.put("mode", outcome.mode().name());
        trace.put("tasks", outcome.plan().tasks());
        trace.put("understoodGoal", outcome.plan().understoodGoal());
        trace.put("transcriptSegments", transcripts.size());
        trace.put("visualEvidence", visuals.size());
        trace.put("chunks", chunks.size());
        trace.put("warnings", run.warnings);
        trace.put("llmEnabled", aiProps.cloudEnabled());

        reportRepository.insert(new AnalysisReport(0, run.jobId, asset.id(), draft.title(), outcome.mode(),
                markdown, Json.write(draft.sections()), Json.write(trace),
                docx == null ? null : docx.toString(), java.time.Instant.now()));

        run.emit(JobStage.REPORT, JobEvent.TYPE_METRIC, "info", JobStage.REPORT.floor(),
                "报告已生成（%d 字）".formatted(markdown.length()));
    }

    private String describeEngine(String asrEngine, int visualCount) {
        String asr = switch (asrEngine) {
            case "cloud-asr" -> "云端语音识别";
            case "whisper" -> "本地 whisper.cpp";
            case "none" -> "无语音";
            default -> asrEngine;
        };
        String ocr = visualCount > 0 ? "本地 OCR" : "无画面文字";
        String llm = aiProps.cloudEnabled() ? "大模型 " + aiProps.getModel() : "离线模板";
        return "%s + %s + %s".formatted(asr, ocr, llm);
    }
}
