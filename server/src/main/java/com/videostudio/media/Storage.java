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

    public Path reportDir(long assetId) {
        return ensure(mediaDir(assetId).resolve("report"));
    }

    public Path reportDocx(long assetId, long jobId) {
        return reportDir(assetId).resolve("report-" + jobId + ".docx");
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
