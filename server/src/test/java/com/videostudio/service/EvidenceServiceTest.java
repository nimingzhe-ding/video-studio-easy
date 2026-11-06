package com.videostudio.service;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.JobCheckpoint;
import com.videostudio.domain.JobStage;
import com.videostudio.domain.JobStatus;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.repository.CheckpointRepository;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import com.videostudio.storage.MediaObjects;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 证据复用：什么时候允许拿别人的转写，什么时候宁可重跑。
 *
 * <p>这里的判定直接决定「讲者信息会不会永久缺失」和「断点续跑会不会白花钱重识别一次」，
 * 所以三种场景都必须钉住：成功任务没标记要重跑、自己的未完成断点要复用、没有语音时不受影响。
 */
class EvidenceServiceTest {

    private static final long ASSET = 5;
    private static final long SELF = 11;

    private final JobRepository jobs = mock(JobRepository.class);
    private final TranscriptRepository transcripts = mock(TranscriptRepository.class);
    private final VisualRepository visuals = mock(VisualRepository.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final ReportRepository reports = mock(ReportRepository.class);
    private final CheckpointRepository checkpoints = mock(CheckpointRepository.class);
    private final MediaObjects objects = mock(MediaObjects.class);
    private final AppProperties props = new AppProperties();

    private EvidenceService service() {
        return new EvidenceService(jobs, transcripts, visuals, chunks, reports, checkpoints, objects, props);
    }

    private AnalysisJob job(long id, JobStatus status) {
        return new AnalysisJob(id, ASSET, AnalysisMode.GENERAL, "USER", null, null, "目标",
                status, JobStage.TRANSCRIBE, 30, null, false, null,
                Instant.now(), Instant.now(), null);
    }

    private void candidate(long id, JobStatus status) {
        when(jobs.listByAsset(ASSET)).thenReturn(List.of(job(id, status)));
    }

    private void speech(long jobId, String speaker, String text) {
        when(transcripts.listByJob(jobId)).thenReturn(List.of(
                new TranscriptSegment(0, 5_000, text, speaker, null, "cloud-asr")));
    }

    private void noFrames(long jobId) {
        when(visuals.listByJob(jobId)).thenReturn(List.of());
        when(chunks.listByJob(jobId)).thenReturn(List.<ContextChunk>of());
    }

    private void reportMarked(long jobId, boolean marked) {
        when(reports.findByJob(jobId)).thenReturn(Optional.of(marked
                ? report(jobId, "{\"speakerSeparation\":true}")
                : report(jobId, "{}")));
    }

    private com.videostudio.domain.AnalysisReport report(long jobId, String trace) {
        return new com.videostudio.domain.AnalysisReport(1, jobId, ASSET, "标题", AnalysisMode.GENERAL,
                "# 正文", "[]", trace, null, Instant.now());
    }

    @Test
    void successfulJobWithoutSeparationMarkIsNotReused() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(7, JobStatus.SUCCEEDED);
        speech(7, null, "这段话没有讲者标签");
        noFrames(7);
        reportMarked(7, false);

        EvidenceService.Reused reused = service().findReusable(ASSET, SELF, false);

        assertFalse(reused.present(), "未启用分离时生成的转写必须重跑一次，否则讲者信息永久缺失");
    }

    @Test
    void successfulJobWithSeparationMarkIsReused() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(7, JobStatus.SUCCEEDED);
        speech(7, "讲者A", "带标签的转写");
        noFrames(7);
        reportMarked(7, true);

        EvidenceService.Reused reused = service().findReusable(ASSET, SELF, false);

        assertTrue(reused.present());
        assertTrue(reused.speakerSeparation());
        assertEquals(7, reused.sourceJobId());
    }

    /**
     * 回归：断点续跑沿用同一个 jobId，上一次失败时没有报告可读。
     * 修复前这里被判定为「没标记」，于是已经落库的转写被跳过、ASR 从头再跑一遍。
     */
    @Test
    void resumingOwnUnfinishedJobReusesItsTranscript() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(SELF, JobStatus.RUNNING);
        speech(SELF, "讲者A", "上次已经识别好的转写");
        noFrames(SELF);
        when(checkpoints.find(SELF)).thenReturn(Optional.of(
                new JobCheckpoint(SELF, JobStage.TRANSCRIBE, "{\"speakerSeparation\":true}", 1, Instant.now())));

        EvidenceService.Reused reused = service().findReusable(ASSET, SELF, true);

        assertTrue(reused.present(), "断点续跑必须复用自己已落库的转写");
        assertEquals(SELF, reused.sourceJobId());
    }

    /** 旧数据的断点没有 payload，但转写确实带标签：按已分离处理，不白跑一次 ASR */
    @Test
    void resumingOwnLegacyCheckpointTrustsExistingSpeakerLabels() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(SELF, JobStatus.RUNNING);
        speech(SELF, "讲者B", "旧版本留下的带标签转写");
        noFrames(SELF);
        when(checkpoints.find(SELF)).thenReturn(Optional.of(
                new JobCheckpoint(SELF, JobStage.TRANSCRIBE, null, 1, Instant.now())));
        when(reports.findByJob(SELF)).thenReturn(Optional.empty());

        assertTrue(service().findReusable(ASSET, SELF, true).present());
    }

    /** 单人素材本来就没有标签：断点 payload 说有标记就不能因为「没标签」被判成需要重跑 */
    @Test
    void singleSpeakerJobIsReusedEvenThoughItHasNoLabels() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(SELF, JobStatus.RUNNING);
        speech(SELF, null, "单人讲解，没有标签");
        noFrames(SELF);
        when(checkpoints.find(SELF)).thenReturn(Optional.of(
                new JobCheckpoint(SELF, JobStage.OCR, "{\"speakerSeparation\":true}", 2, Instant.now())));

        assertTrue(service().findReusable(ASSET, SELF, true).present(),
                "没有标签不等于没做过分离，单人素材不该被反复重跑");
    }

    /** 自己的断点没标记、转写也没标签：只能重跑，否则讲者信息补不回来 */
    @Test
    void resumingOwnUnmarkedJobWithoutLabelsIsNotReused() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(SELF, JobStatus.RUNNING);
        speech(SELF, null, "旧版本、无标记、无标签");
        noFrames(SELF);
        when(checkpoints.find(SELF)).thenReturn(Optional.of(
                new JobCheckpoint(SELF, JobStage.TRANSCRIBE, null, 1, Instant.now())));
        when(reports.findByJob(SELF)).thenReturn(Optional.empty());

        assertFalse(service().findReusable(ASSET, SELF, true).present());
    }

    /** 分离开关关掉后，任何转写都可以直接复用，不该再要求标记 */
    @Test
    void separationDisabledSkipsTheMarkCheckEntirely() {
        props.getMedia().setSpeakerSeparationEnabled(false);
        candidate(7, JobStatus.SUCCEEDED);
        speech(7, null, "没有标签也无所谓");
        noFrames(7);
        when(reports.findByJob(7)).thenReturn(Optional.empty());

        assertTrue(service().findReusable(ASSET, SELF, false).present());
    }

    /** 没有语音、只有画面证据时不该进入说话人判定 */
    @Test
    void visualOnlyJobIsReusedWithoutTranscript() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(7, JobStatus.SUCCEEDED);
        when(transcripts.listByJob(7)).thenReturn(List.of());
        when(visuals.listByJob(7)).thenReturn(List.of(
                new VisualEvidence(1_000, "/tmp/1.jpg", "画面文字", null, "h", "ocr")));
        when(objects.materializeFrame(anyLong(), anyLong(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(chunks.listByJob(7)).thenReturn(List.<ContextChunk>of());

        assertTrue(service().findReusable(ASSET, SELF, false).present());
    }

    /** 不允许复用自己时（正常的新任务），未完成的自己不能被当成来源 */
    @Test
    void selfIsIgnoredWhenNotResuming() {
        props.getMedia().setSpeakerSeparationEnabled(false);
        candidate(SELF, JobStatus.RUNNING);
        speech(SELF, "讲者A", "正在跑的任务的转写");
        noFrames(SELF);

        assertFalse(service().findReusable(ASSET, SELF, false).present());
    }

    /** 合并过碎的片段之后，只要有一段带标签就认为整份转写带讲者信息 */
    @Test
    void anyLabelledSegmentCountsAsSeparated() {
        props.getMedia().setSpeakerSeparationEnabled(true);
        candidate(SELF, JobStatus.RUNNING);
        when(transcripts.listByJob(SELF)).thenReturn(List.of(
                new TranscriptSegment(0, 1_000, "第一句", "讲者A", null, "cloud-asr"),
                new TranscriptSegment(1_000, 2_000, "第二句", null, null, "cloud-asr")));
        noFrames(SELF);
        when(checkpoints.find(SELF)).thenReturn(Optional.of(
                new JobCheckpoint(SELF, JobStage.TRANSCRIBE, null, 1, Instant.now())));
        when(reports.findByJob(SELF)).thenReturn(Optional.empty());

        assertTrue(service().findReusable(ASSET, SELF, true).present());
    }
}
