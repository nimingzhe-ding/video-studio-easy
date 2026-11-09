package com.videostudio.media;

import com.videostudio.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链接导入的准入校验。
 *
 * <p>这是防 SSRF 的第一道防线：服务端会替用户访问任意 URL，
 * 如果不拦住内网地址，攻击者可以用它探测云元数据端点（169.254.169.254）
 * 或扫描内网服务。这里钉住必须被拒绝的几类地址。
 */
class YtDlpDownloaderTest {

    private final YtDlpDownloader downloader = new YtDlpDownloader(
            new ProcessRunner(), new AppProperties(), "yt-dlp", "", "", 60);

    @Test
    void rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> downloader.download("file:///etc/passwd", Path.of("/tmp")));
        assertThrows(IllegalArgumentException.class, () -> downloader.download("ftp://example.com/a.mp4", Path.of("/tmp")));
        assertThrows(IllegalArgumentException.class, () -> downloader.download("not a url", Path.of("/tmp")));
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class, () -> downloader.download("  ", Path.of("/tmp")));
        assertThrows(IllegalArgumentException.class, () -> downloader.download(null, Path.of("/tmp")));
    }

    /** 云元数据端点是 SSRF 攻击的首要目标，必须被拦截 */
    @Test
    void rejectsCloudMetadataEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> downloader.download("http://169.254.169.254/latest/meta-data/", Path.of("/tmp")));
    }

    @Test
    void rejectsLoopbackAndPrivateRanges() {
        for (String url : new String[]{
                "http://127.0.0.1:9090/api/jobs",
                "http://localhost/admin",
                "http://10.0.0.5/internal",
                "http://192.168.1.1/router",
                "http://172.16.0.1/x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> downloader.download(url, Path.of("/tmp")), "必须拒绝: " + url);
        }
    }

    /** 公网地址不应在准入阶段被拒绝（真正的下载另说） */
    @Test
    void acceptsPublicHosts() {
        // 只验证「没有在准入校验就被拒」，不真正发起下载
        try {
            downloader.download("https://www.bilibili.com/video/BV1xx411c7mD", Path.of("/tmp/never"));
        } catch (IllegalArgumentException e) {
            throw new AssertionError("公网链接不该被准入校验拒绝: " + e.getMessage());
        } catch (RuntimeException expected) {
            // 二进制不存在或下载失败都属预期
        }
    }

    @Test
    void disabledWhenBinaryIsBlank() {
        assertFalse(new YtDlpDownloader(new ProcessRunner(), new AppProperties(), "", "", "", 60).enabled());
        assertTrue(downloader.enabled());
    }
}
