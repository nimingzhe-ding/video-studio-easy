package com.videostudio.media;

import com.videostudio.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本地目录约定：所有派生文件都放在 {root}/media/{assetId}/ 下，方便整目录清理 */
@Component
public class Storage {

    private final Path root;

    public Storage(AppProperties props) {
        this.root = Path.of(props.getStorage().getRoot()).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建目录: " + dir, e);
        }
    }

    public Path mediaDir(long assetId) {
        return ensure(root.resolve("media").resolve(Long.toString(assetId)));
    }

    public Path sourceFile(long assetId, String originalName) {
        return mediaDir(assetId).resolve("source" + extension(originalName));
    }

    /** 抽取出的 16k 单声道音轨，同样按任务隔离 */
    public Path audioFile(long assetId, long jobId) {
        return ensure(mediaDir(assetId).resolve("audio").resolve("job-" + jobId)).resolve("audio.wav");
    }

    /** 转写切片目录：按任务隔离，避免同一素材并发分析时互相覆盖 */
    public Path asrDir(long assetId, long jobId) {
        return ensure(mediaDir(assetId).resolve("asr").resolve("job-" + jobId));
    }

    /** 关键帧目录：同样按任务隔离，抽帧会先清空本任务目录 */
    public Path frameDir(long assetId, long jobId) {
        return ensure(mediaDir(assetId).resolve("frames").resolve("job-" + jobId));
    }

    /** 分片上传会话目录：与正式媒体分开，合并成功后才搬到 media/{assetId} */
    public Path uploadDir(String uploadId) {
        return ensure(root.resolve("uploads").resolve(safe(uploadId)));
    }

    /** 单个分片文件，统一零填充命名，便于按序号遍历 */
    public Path uploadChunk(String uploadId, int index) {
        return uploadDir(uploadId).resolve("chunk-%06d.part".formatted(index));
    }

    public Path uploadManifest(String uploadId) {
        return uploadDir(uploadId).resolve("session.json");
    }

    public Path reportDir(long assetId) {
        return ensure(mediaDir(assetId).resolve("report"));
    }

    public Path reportDocx(long assetId, long jobId) {
        return reportDir(assetId).resolve("report-" + jobId + ".docx");
    }

    /** uploadId 来自客户端，必须剔除路径分隔符，避免目录穿越 */
    private String safe(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            throw new IllegalArgumentException("非法的 uploadId");
        }
        return cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
    }

    /** 取扩展名（含点），未知时退化为 .bin */
    public String extension(String name) {
        if (name == null) {
            return ".bin";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return ".bin";
        }
        String ext = name.substring(dot).toLowerCase();
        return ext.length() > 10 ? ".bin" : ext;
    }
}
