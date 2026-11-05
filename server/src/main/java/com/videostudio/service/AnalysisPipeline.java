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
import com.videostudio.config.AppProperties;
import com.videostudio.config.AiProperties;
import com.videostudio.context.ChunkBuilder;
import com.videostudio.context.ChunkEnricher;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.AnalysisReport;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.JobEvent;
import com.videostudio.domain.JobCheckpoint;
import com.videostudio.domain.JobStage;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.MediaKind;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.infra.BudgetGuard;
import com.videostudio.infra.JobEventBus;
import com.videostudio.infra.JobMetrics;
import com.videostudio.media.AudioExtractor;
import com.videostudio.media.FrameExtractor;
import com.videostudio.media.MediaInfo;
import com.videostudio.media.MediaProbe;
import com.videostudio.media.Storage;
import com.videostudio.retrieval.VectorStore;
import com.videostudio.storage.MediaObjects;
import com.videostudio.ocr.VisualEvidenceService;
import com.videostudio.report.DocxExporter;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.MediaRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.CheckpointRepository;
import com.videostudio.repository.VisualRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 分析流水线：探针 → 音频/转写 ∥ 关键帧/OCR → 分块索引 → Agent → 报告落库。
 * <p>每个阶段都会推进进度并写一条 SSE 事件，前端因此可以实时看到「现在在做什么」。
 */
@Service
public class AnalysisPipeline {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipeline.class);

    /** 证据时间戳与抽帧时刻的容差：ASR 段起点常常比抽到的那一帧晚零点几秒 */
    private static final long FIGURE_TOLERANCE_MS = 2_000;

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
    private final EvidenceService evidenceService;
    private final DocxExporter docxExporter;
    private final Storage storage;
    private final MediaObjects objects;
    private final VectorStore vectors;
    private final AgentProperties agentProps;
    private final AiProperties aiProps;
    private final AppProperties appProps;
    private final JobEventBus eventBus;
    private final JobMetrics metrics;
    private final BudgetGuard budget;
    private final CheckpointRepository checkpoints;

    public AnalysisPipeline(MediaRepository mediaRepository, JobRepository jobRepository,
                            JobEventRepository eventRepository, TranscriptRepository transcriptRepository,
                            VisualRepository visualRepository, ChunkRepository chunkRepository,
                            ReportRepository reportRepository, MediaProbe probe, AudioExtractor audioExtractor,
                            FrameExtractor frameExtractor, TranscriptionService transcriptionService,
                            VisualEvidenceService visualService, ChunkEnricher chunkEnricher,
                            AnalysisAgent agent, EvidenceService evidenceService, DocxExporter docxExporter,
                            Storage storage, MediaObjects objects, VectorStore vectors,
                            AgentProperties agentProps, AiProperties aiProps, AppProperties appProps,
                            JobEventBus eventBus, JobMetrics metrics, BudgetGuard budget,
                            CheckpointRepository checkpoints) {
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
        this.evidenceService = evidenceService;
        this.docxExporter = docxExporter;
        this.storage = storage;
        this.objects = objects;
        this.vectors = vectors;
        this.agentProps = agentProps;
        this.aiProps = aiProps;
        this.appProps = appProps;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.budget = budget;
        this.checkpoints = checkpoints;
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
        /** 上一个阶段的开始时刻，用于统计阶段耗时 */
        java.time.Instant stageStarted = java.time.Instant.now();
        /** 第几次尝试（断点续跑时会 >1），只用于诊断与上报 */
        int attempts = 1;
        /** 本次是否在续跑上一个未完成的断点 */
        boolean resuming = false;

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

        synchronized void emit(JobStage stage, String type, String level, int progress, String message) {
            if(stage!=JobStage.FAILED && stage!=JobStage.DONE &&
                jobRepository.find(jobId).map(j -> j.status()==com.videostudio.domain.JobStatus.CANCELED).orElse(true)) {
                throw new java.util.concurrent.CancellationException("任务已取消");
            }
            long next = seq.incrementAndGet();
            JobEvent event = new JobEvent(0, stage, type, level, progress, message, null, java.time.Instant.now());
            eventRepository.append(jobId, next, event);
            // 广播「有新事件」的信号：同实例的 SSE 立刻醒来，其他实例靠 Redis pub/sub 也立刻醒来。
            // 事件本身以数据库为准，所以这里丢消息最多退化成一次轮询。
            eventBus.publish(jobId, next);
        }

        void stage(JobStage stage, String message) {
            // 换阶段时结算上一段耗时：能一眼看出瓶颈在转写、抽帧还是模型生成
            java.time.Instant now = java.time.Instant.now();
            if (stageStarted != null) {
                metrics.stage(current.name().toLowerCase(java.util.Locale.ROOT),
                        java.time.Duration.between(stageStarted, now));
                // 进入新阶段说明上一阶段已经做完，这里落一次断点。
                // 断点写入失败时停止，保留可恢复的任务状态。
                checkpoints.save(jobId,current,null,attempts);
            }
            stageStarted = now;
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
        Path source = objects.ensureSource(asset.id(),asset.name(),storage.sourceFile(asset.id(),asset.name()));
        Run run = new Run(job, asset, source);
        java.time.Instant started = java.time.Instant.now();
        metrics.jobStarted(job.mode().name());
        // 把 jobId 绑到线程上：模型网关记账、日志 MDC 都靠它，省去逐层传参
        budget.begin(jobId);
        budget.enter(jobId);
        org.slf4j.MDC.put("jobId", Long.toString(jobId));
        String status = "failed";
        try {
            jobRepository.markRunning(jobId);
            execute(run, job, asset, source);
            if(!jobRepository.markSucceeded(jobId)) throw new java.util.concurrent.CancellationException("任务已取消");
            run.emit(JobStage.DONE, JobEvent.TYPE_DONE, "info", 100, "分析完成");
            // DONE 是终态，不走 stage()，所以断点要在这里显式落一次；
            // 否则断点会永远停在最后一个真实阶段，重试时白跑一遍报告
            checkpoints.save(jobId,JobStage.DONE,null,run.attempts);
            status = "succeeded";
        } catch (Exception e) {
            log.error("任务 {} 失败", jobId, e);
            String message = Texts.oneLine(e.getMessage() == null ? e.toString() : e.getMessage(), 300);
            jobRepository.markFailed(jobId, message);
            run.emit(JobStage.FAILED, JobEvent.TYPE_ERROR, "error", 100, "分析失败：" + message);
            throw new IllegalStateException(message,e);
        } finally {
            // 顺序很重要：先结算阶段耗时，再清上下文，最后回收记账
            if (run.stageStarted != null) {
                metrics.stage(run.current.name().toLowerCase(java.util.Locale.ROOT),
                        java.time.Duration.between(run.stageStarted, java.time.Instant.now()));
            }
            metrics.jobFinished(job.mode().name(), status, java.time.Duration.between(started, java.time.Instant.now()));
            org.slf4j.MDC.remove("jobId");
            budget.leave();
            budget.end(jobId);
        }
    }

    private void execute(Run run, AnalysisJob job, MediaAsset asset, Path source) throws Exception {
        // 断点必须在任何 stage() 之前读：stage() 自己也会写断点，
        // 放到后面读会让「刚提交的新任务」误判成续跑（实测踩过这个坑）。
        JobStage resumeFrom = checkpoints.find(run.jobId)
                .filter(checkpoint -> checkpoint.stage() != JobStage.DONE)
                .map(JobCheckpoint::stage)
                .orElse(null);
        run.resuming = resumeFrom != null;
        if (resumeFrom != null) {
            run.attempts = checkpoints.find(run.jobId)
                    .map(checkpoint -> Math.max(1, checkpoint.attempts() + 1))
                    .orElse(2);
        }
        run.stage(JobStage.PROBE, "解析媒体信息");
        if (resumeFrom != null) {
            run.emit(JobStage.PROBE, JobEvent.TYPE_LOG, "info", JobStage.PROBE.floor(),
                    "断点续跑（第 %d 次）：上次完成到「%s」，已完成的阶段会跳过"
                            .formatted(run.attempts, resumeFrom.label()));
        }
        if (!java.nio.file.Files.isReadable(source)) {
            throw new IllegalStateException("源文件不存在或不可读: " + source);
        }
        MediaInfo info = probe.probe(source.toString());
        run.durationMs = info.durationMs() > 0 ? info.durationMs() : run.durationMs;
        run.emit(JobStage.PROBE, JobEvent.TYPE_METRIC, "info", JobStage.PROBE.floor(),
                "时长 %s，分辨率 %s".formatted(Times.human(run.durationMs),
                        info.width() == null ? "音频" : info.width() + "x" + info.height()));

        // ---- 证据复用：转写与 OCR 只取决于素材内容，与本次目标无关，同素材已有成功任务就直接拿来用
        // 断点续跑时允许复用本任务自己的证据：重试沿用同一个 jobId，
        // 上次失败前已落库的转写与画面就在自己名下，排除自己等于每次重试都从零开始。
        if (run.resuming) {
            run.warn("检测到未完成的断点（第 %d 次尝试），继续复用已完成的证据".formatted(run.attempts));
        }
        EvidenceService.Reused reused = evidenceService.findReusable(asset.id(), run.jobId, run.resuming);
        if (reused.present() && !evidenceService.framesAvailable(reused.visuals())) {
            run.warn("历史关键帧文件已被清理，本次重新抽取画面证据");
            reused = new EvidenceService.Reused(reused.sourceJobId(), reused.segments(), List.of(), List.of(),
                    reused.speakerSeparation());
        }

        // ---- 语音：抽音频 → 切片 → ASR
        List<TranscriptSegment> transcripts = List.of();
        String asrEngine = "none";
        // 只有真正跑过 ASR 且开着说话人分离，才算「这份转写已经带有说话人信息」
        boolean separated = false;
        if (!reused.segments().isEmpty()) {
            transcripts = reused.segments();
            separated = reused.speakerSeparation();
            asrEngine = transcripts.get(0).source() == null ? "reused" : transcripts.get(0).source();
            transcriptRepository.replace(asset.id(), run.jobId, transcripts);
            run.emit(JobStage.TRANSCRIBE, JobEvent.TYPE_LOG, "info", JobStage.TRANSCRIBE.floor(),
                    "复用任务 %d 的转写（%d 段），跳过语音识别".formatted(reused.sourceJobId(), transcripts.size()));
        } else if (info.hasAudio()) {
            run.stage(JobStage.AUDIO, "抽取音频轨");
            Path wav = audioExtractor.extract(source, storage.audioFile(asset.id(), run.jobId));
            run.stage(JobStage.TRANSCRIBE, "语音识别中");
            TranscriptionService.Outcome outcome = transcriptionService.transcribe(wav, storage.asrDir(asset.id(), run.jobId),
                    run.durationMs, run.progress(JobStage.TRANSCRIBE));
            transcripts = outcome.segments();
            asrEngine = outcome.engine();
            separated = appProps.getMedia().isSpeakerSeparationEnabled();
            outcome.warnings().forEach(run::warn);
            transcriptRepository.replace(asset.id(), run.jobId, transcripts);
            String speakerNote = outcome.speakers() > 1 ? "，%d 位说话人".formatted(outcome.speakers()) : "";
            run.emit(JobStage.TRANSCRIBE, JobEvent.TYPE_METRIC, "info", JobStage.TRANSCRIBE.floor(),
                    "转写 %d 段（引擎 %s%s）".formatted(transcripts.size(), asrEngine, speakerNote));
        } else {
            run.warn("该文件没有音频轨，跳过语音识别");
        }

        // ---- 画面：关键帧 → OCR
        List<VisualEvidence> visuals = List.of();
        String visualEngine = null;
        if (!reused.visuals().isEmpty()) {
            visuals = evidenceService.copyFrames(reused.visuals(), storage.frameDir(asset.id(), run.jobId));
            visualEngine = visualService.engine();
            visualRepository.replace(asset.id(), run.jobId, visuals);
            run.emit(JobStage.OCR, JobEvent.TYPE_LOG, "info", JobStage.OCR.floor(),
                    "复用任务 %d 的画面文字（%d 条），跳过抽帧与识别"
                            .formatted(reused.sourceJobId(), visuals.size()));
        } else if (info.hasVideo() && asset.kind() == MediaKind.VIDEO) {
            run.stage(JobStage.FRAMES, "抽取关键帧");
            List<FrameExtractor.Frame> frames = frameExtractor.extract(source, storage.frameDir(asset.id(), run.jobId), run.durationMs);
            run.emit(JobStage.FRAMES, JobEvent.TYPE_METRIC, "info", JobStage.FRAMES.floor(),
                    "抽取关键帧 %d 张".formatted(frames.size()));
            if (!frames.isEmpty()) {
                run.stage(JobStage.OCR, "识别画面文字");
                VisualEvidenceService.Outcome ocr = visualService.extract(frames, run.progress(JobStage.OCR));
                visuals = ocr.items();
                if (!visuals.isEmpty()) {
                    visualEngine = visualService.engine();
                }
                visualRepository.replace(asset.id(), run.jobId, visuals);
                String ocrNote = ocr.captioned() > 0
                        ? "画面证据 %d 条（其中 %d 条为视觉描述，去重丢弃 %d 帧）".formatted(visuals.size(), ocr.captioned(), ocr.dropped())
                        : "画面文字 %d 条（去重丢弃 %d 帧）".formatted(visuals.size(), ocr.dropped());
                run.emit(JobStage.OCR, JobEvent.TYPE_METRIC, "info", JobStage.OCR.floor(), ocrNote);
            }
        }

        if (transcripts.isEmpty() && visuals.isEmpty()) {
            throw new IllegalStateException("未能从该媒体中获得任何可用证据（语音与画面文字均为空），请检查文件内容或本地工具配置");
        }

        // ---- 分块与索引
        run.stage(JobStage.SEGMENT, "对齐时间轴");
        run.stage(JobStage.CHUNK, "构建上下文分块");
        List<ContextChunk> chunks;
        if (reused.hasChunks() && !reused.segments().isEmpty() && !reused.visuals().isEmpty()) {
            // 分块依赖的只是转写与画面文字，两者都没变时分块也不会变；复用可顺带省掉摘要与向量化的模型调用
            chunks = reused.chunks();
            chunkRepository.replace(asset.id(), run.jobId, chunks, aiProps.getEmbeddingModel());
            run.emit(JobStage.CHUNK, JobEvent.TYPE_LOG, "info", JobStage.CHUNK.floor(),
                    "复用任务 %d 的 %d 个上下文分块".formatted(reused.sourceJobId(), chunks.size()));
        } else {
            chunks = ChunkBuilder.build(transcripts, visuals, run.durationMs, agentProps);
            chunks = chunkEnricher.enrich(chunks, run.progress(JobStage.CHUNK));
            chunkRepository.replace(asset.id(), run.jobId, chunks, aiProps.getEmbeddingModel());
            run.emit(JobStage.CHUNK, JobEvent.TYPE_METRIC, "info", JobStage.CHUNK.floor(),
                    "生成 %d 个上下文分块".formatted(chunks.size()));
        }
        // 向量库只是语义检索的加速器：写失败不影响后续，检索端会自动退回内存计算
        if (vectors.enabled()) {
            vectors.index(asset.id(), run.jobId, chunks);
            long withVector = chunks.stream().filter(c -> !c.embedding().isEmpty()).count();
            run.emit(JobStage.CHUNK, JobEvent.TYPE_LOG, "info", JobStage.CHUNK.floor(),
                    "向量索引 %s：%d/%d 个分块".formatted(vectors.provider(), withVector, chunks.size()));
        }

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
                        describeEngine(asrEngine, visuals.size(), captionCount(visuals)),
                        Texts.isBlank(job.modeReason()) ? outcome.modeReason() : job.modeReason(),
                        outcome.rounds(), verified),
                draft, outcome.critique(), citations);

        Path docx = storage.reportDocx(asset.id(), run.jobId);
        List<DocxExporter.Figure> figures = reportFigures(draft, visuals);
        docxExporter.export(markdown,docx,figures);
        objects.putReport(asset.id(),run.jobId,docx);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("engine", outcome.engine());
        trace.put("asrEngine", asrEngine);
        if (visualEngine != null) {
            trace.put("visualEngine", visualEngine);
        }
        trace.put("rounds", outcome.rounds());
        trace.put("verified", verified);
        trace.put("mode", outcome.mode().name());
        if (!Texts.isBlank(job.modeSource())) {
            trace.put("modeSource", job.modeSource());
        }
        if (reused.present()) {
            trace.put("reusedFrom", reused.sourceJobId());
        }
        trace.put("tasks", outcome.plan().tasks());
        trace.put("understoodGoal", outcome.plan().understoodGoal());
        trace.put("transcriptSegments", transcripts.size());
        // 记下这份转写是否已经带有说话人信息：证据复用据此判断旧任务要不要重新识别
        if (separated) {
            trace.put("speakerSeparation", true);
        }
        int speakerCount = speakers(transcripts);
        if (speakerCount > 1) {
            trace.put("speakers", speakerCount);
        }
        trace.put("visualEvidence", visuals.size());
        int captionedCount = captionCount(visuals);
        if (captionedCount > 0) {
            trace.put("captionedFrames", captionedCount);
        }
        trace.put("chunks", chunks.size());
        if (!figures.isEmpty()) {
            trace.put("docxFigures", figures.size());
        }
        trace.put("warnings", run.warnings);
        trace.put("llmEnabled", aiProps.cloudEnabled());

        reportRepository.insert(new AnalysisReport(0, run.jobId, asset.id(), draft.title(), outcome.mode(),
                markdown, Json.write(draft.sections()), Json.write(trace),
                objects.reportKey(asset.id(),run.jobId), java.time.Instant.now()));

        run.emit(JobStage.REPORT, JobEvent.TYPE_METRIC, "info", JobStage.REPORT.floor(),
                "报告已生成（%d 字）".formatted(markdown.length()));
    }

    private String describeEngine(String asrEngine, int visualCount, int captionedCount) {
        String asr = switch (asrEngine) {
            case "cloud-asr" -> "云端语音识别";
            case "none" -> "无语音";
            default -> asrEngine;
        };
        String ocr = visualCount <= 0 ? "无画面文字"
                : captionedCount > 0 ? "本地 OCR + 视觉描述 %d 帧".formatted(captionedCount)
                : "本地 OCR";
        String llm = "大模型 " + aiProps.getModel();
        return "%s + %s + %s".formatted(asr, ocr, llm);
    }

    /** 不同说话人的数量；没有标签时返回 0 */
    private static int speakers(List<TranscriptSegment> transcripts) {
        Set<String> names = new LinkedHashSet<>();
        for (TranscriptSegment segment : transcripts) {
            if (!Texts.isBlank(segment.speaker())) {
                names.add(segment.speaker());
            }
        }
        return names.size();
    }

    private static int captionCount(List<VisualEvidence> visuals) {
        int count = 0;
        for (VisualEvidence visual : visuals) {
            if (visual.captionOnly()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 挑出要插进 Word 的关键帧。
     * <p>顺序是「被结论引用的帧优先，其余按时长均匀取样补足」，两条理由：
     * <ul>
     *   <li>读者真正要核对的是结论引用的那一帧，所以先放被引用的；</li>
     *   <li>只放被引用的又会漏掉没被写进结论的章节（比如纯画面演示），
     *       而两小时视频有几百帧全塞进去会淹没正文，所以剩下的名额按时间均匀取样。</li>
     * </ul>
     */
    private List<DocxExporter.Figure> reportFigures(AgentModels.Draft draft, List<VisualEvidence> visuals) {
        int limit = agentProps.getMaxReportFigures();
        if (limit <= 0 || visuals.isEmpty()) {
            return List.of();
        }
        List<VisualEvidence> usable = visuals.stream()
                .filter(v -> !Texts.isBlank(v.framePath()) && Files.isReadable(Path.of(v.framePath())))
                .sorted(Comparator.comparingLong(VisualEvidence::timeMs))
                .toList();
        if (usable.isEmpty()) {
            return List.of();
        }
        // 1) 草稿里真的引用了的帧（OCR / VISION 证据，时间戳按 2 秒容差对齐）
        List<VisualEvidence> picked = new ArrayList<>();
        for (VisualEvidence visual : usable) {
            boolean cited = draft.evidence().stream()
                    .filter(e -> e.source() != null && (e.source().contains("OCR") || e.source().contains("VISION")))
                    .anyMatch(e -> Math.abs(visual.timeMs() - e.timestampMs()) <= FIGURE_TOLERANCE_MS);
            if (cited) {
                picked.add(visual);
            }
            if (picked.size() >= limit) {
                break;
            }
        }
        // 2) 名额还有剩就按时间均匀取样，保证长视频也能看到片头片尾
        if (picked.size() < limit) {
            List<VisualEvidence> rest = usable.stream().filter(v -> !picked.contains(v)).toList();
            int need = limit - picked.size();
            if (rest.size() <= need) {
                picked.addAll(rest);
            } else {
                for (int k = 0; k < need; k++) {
                    picked.add(rest.get((int) ((long) k * rest.size() / need)));
                }
            }
        }
        return picked.stream()
                .sorted(Comparator.comparingLong(VisualEvidence::timeMs))
                .map(AnalysisPipeline::figure)
                .toList();
    }

    private static DocxExporter.Figure figure(VisualEvidence visual) {
        String caption = Texts.isBlank(visual.ocrText()) ? visual.caption() : visual.ocrText();
        return new DocxExporter.Figure(visual.timeMs(), Path.of(visual.framePath()), caption);
    }
}
