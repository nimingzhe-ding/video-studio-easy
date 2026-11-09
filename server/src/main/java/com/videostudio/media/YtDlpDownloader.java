package com.videostudio.media;

import com.videostudio.common.Secrets;
import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用 yt-dlp 把网页视频链接下载成可分析的本地文件。
 *
 * <p>支持 B 站、YouTube 等站点。下载目标固定为「≤1080p、≤30fps 的 H.264 + AAC MP4」：
 * 分析链路只消费音轨和关键帧，4K60 对结果没有任何增益，体积却可能涨近十倍，
 * 反而容易被站点限速掐断。
 *
 * <p>风控是真实存在的：B 站对机房 IP 的匿名请求直接返回 412。仅靠 User-Agent 绕不过，
 * 必须注入真实浏览器会话。这里支持两种 cookie 来源，显式文件优先——
 * 服务端用文件更可控，也不依赖运行时能否读到浏览器 cookie 存储。
 */
@Component
public class YtDlpDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);
    /** yt-dlp 的进度行用 \r 原地刷新，一个百兆视频能刷出上百行 */
    private static final Pattern PROGRESS = Pattern.compile("^\\[download]\\s+[\\d.]+% of ");
    /**
     * 格式选择：优先 H.264 + AAC 的 MP4。
     *
     * <p>为什么按编解码器而不是 ext 过滤音轨：B 站的音轨 ext 是 {@code m4a}（audio only），
     * 若写成 {@code +ba[ext=m4a]} 看似对，但合并进 mp4 容器后 ext 语义就变了；
     * 用 {@code acodec^=mp4a} 判断 AAC 更准确，也不会因为站点把容器标成 webm 而失配。
     *
     * <p>分层降级依次是：≤1080p 且 ≤30fps → ≤1080p → 任意 avc1 → 任意最佳。
     * 分析只消费音轨与关键帧，4K60 没有收益，体积却可能涨十倍、更容易被限速掐断。
     */
    private static final String FORMAT = String.join("/",
            "bv*[vcodec^=avc1][height<=1080][fps<=30]+ba[acodec^=mp4a]",
            "bv*[vcodec^=avc1][height<=1080]+ba[acodec^=mp4a]",
            "b[vcodec^=avc1][height<=1080]",
            "bv*[vcodec^=avc1]+ba[acodec^=mp4a]",
            "b[vcodec^=avc1]",
            // 直链 mp4 只有单档格式，上面全部匹配不上时靠它兜底
            "b");

    private final String binary;
    private final String ffmpegPath;
    private final String cookiesFile;
    private final String cookiesFromBrowser;
    private final long maxBytes;
    private final int timeoutSeconds;
    private final ProcessRunner runner;

    public YtDlpDownloader(ProcessRunner runner, AppProperties props,
                           @Value("${tool.ytdlp.path:yt-dlp}") String binary,
                           @Value("${tool.ytdlp.cookies:}") String cookiesFile,
                           @Value("${tool.ytdlp.cookies-from-browser:}") String cookiesFromBrowser,
                           @Value("${tool.ytdlp.timeout-seconds:1800}") int timeoutSeconds) {
        this.runner = runner;
        this.binary = binary;
        this.ffmpegPath = props.getMedia().getFfmpeg();
        this.cookiesFile = cookiesFile;
        this.cookiesFromBrowser = cookiesFromBrowser;
        this.maxBytes = props.getStorage().getMaxUploadBytes();
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 该功能是否已配置可用（二进制存在） */
    public boolean enabled() {
        return binary != null && !binary.isBlank();
    }

    public record Downloaded(Path file, String title, long bytes) {
    }

    /**
     * 下载到 {@code targetDir}，文件名由链接派生。
     *
     * @throws IllegalArgumentException 链接不合法或指向内网
     * @throws IllegalStateException    下载失败，消息里带可读原因
     */
    public Downloaded download(String url, Path targetDir) {
        validatePublicUrl(url);
        Path output = targetDir.resolve(UUID.randomUUID() + ".mp4");
        // yt-dlp 会写回 cookie 文件刷新凭证，而生产环境把它只读挂载，所以先复制到可写目录
        Path workDir = targetDir.resolve("work-" + UUID.randomUUID());
        List<String> command = new ArrayList<>(List.of(
                binary,
                "--no-playlist",
                "--socket-timeout", "30",
                "--retries", "10",
                // 分片下载的失败模式是单分片反复断连，与整文件重试不是一回事
                "--fragment-retries", "10",
                "--max-filesize", Math.max(1, maxBytes / (1024 * 1024)) + "M",
                "-f", FORMAT,
                "--merge-output-format", "mp4",
                "--recode-video", "mp4"));
        // 合并音视频轨需要 ffmpeg。只有配置成绝对路径时才显式告知 yt-dlp ——
        // 传一个目录会让某些 yt-dlp 版本判定「未安装 ffmpeg」；
        // 默认值 "ffmpeg" 本身就是让它走 PATH 查找，反而是最可靠的方式。
        if (ffmpegPath != null && Path.of(ffmpegPath).isAbsolute() && Files.isExecutable(Path.of(ffmpegPath))) {
            command.add("--ffmpeg-location");
            command.add(ffmpegPath);
        }
        appendCookies(command, workDir);
        command.add("-o");
        command.add(output.toString());
        command.add(url);

        try {
            Files.createDirectories(targetDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建下载目录: " + targetDir, e);
        }

        ProcessRunner.Result result = runner.run(command, Duration.ofSeconds(timeoutSeconds));
        if (!result.ok() || !Files.isRegularFile(output)) {
            deleteQuietly(output);
            throw new IllegalStateException("视频链接下载失败：" + readableFailure(result));
        }
        try {
            return new Downloaded(output, url, Files.size(output));
        } catch (java.io.IOException e) {
            deleteQuietly(output);
            throw new IllegalStateException("读取下载文件失败", e);
        }
    }

    /**
     * 两种 cookie 来源互斥，显式文件优先。
     *
     * <p>cookie 文件会被复制到 {@code workDir} 再使用：yt-dlp 会<b>写回</b>这个文件来刷新
     * cookie，而生产环境通常用只读卷挂载它（避免容器篡改宿主机凭证）。
     * 直接在只读路径上运行会抛 {@code OSError: Read-only file system}。
     *
     * @param workDir 本次下载可写的临时目录；为 null 时退化为直接使用原路径
     */
    private void appendCookies(List<String> command, Path workDir) {
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            String usable = cookiesFile;
            if (workDir != null) {
                Path source = Path.of(cookiesFile);
                if (Files.isReadable(source)) {
                    Path copy = workDir.resolve("cookies.txt");
                    try {
                        Files.createDirectories(workDir);
                        Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        usable = copy.toString();
                    } catch (java.io.IOException e) {
                        log.warn("cookie 文件复制失败，直接使用原路径: {}", e.getMessage());
                    }
                }
            }
            command.add("--cookies");
            command.add(usable);
            return;
        }
        if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser);
        }
    }

    /**
     * 只接受公网 http/https 链接。
     *
     * <p>这是防 SSRF 的第一道也是唯一一道应用层防线：拦住本机、内网、链路本地
     * （含云厂商元数据端点 169.254.169.254）与保留网段。但 yt-dlp 是独立进程，
     * 会对主机重新做 DNS 解析并可能跟随重定向，存在 DNS rebinding 的 TOCTOU 窗口——
     * 生产环境必须叠加网络层出口管控，单靠这一层不足以杜绝。
     */
    private void validatePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("视频链接不能为空");
        }
        URI uri;
        try {
            uri = URI.create(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("视频链接格式不正确");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持公网 http/https 视频链接");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("无法解析链接的主机地址: " + host);
        }
        for (InetAddress address : addresses) {
            if (isDisallowed(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网或保留网段地址");
            }
        }
    }

    private static boolean isDisallowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) {
                return true;                                   // 0.0.0.0/8
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;                                   // 100.64.0.0/10 运营商级 NAT
            }
            if (first == 169 && second == 254) {
                return true;                                   // 169.254.0.0/16（云元数据）
            }
            return first >= 240;                               // 240.0.0.0/4 保留
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;                  // fc00::/7 ULA
        }
        return false;
    }

    /**
     * 把 yt-dlp 的失败输出压成一句可读原因。
     * <p>先剔除进度行：进度用 \r 原地刷新，一个视频能刷上百行，
     * 直接取尾部会把真正的报错挤出窗口（只剩满屏百分比）。
     */
    private String readableFailure(ProcessRunner.Result result) {
        String raw = result.stderr() == null || result.stderr().isBlank() ? result.stdout() : result.stderr();
        if (raw == null || raw.isBlank()) {
            return result.timedOut() ? "下载超时" : "yt-dlp 未返回任何输出";
        }
        String cleaned = raw.lines()
                .filter(line -> !PROGRESS.matcher(line.trim()).find())
                .reduce((a, b) -> b)
                .orElse(raw);
        // 日志里可能带 cookie 相关的敏感信息
        String safe = Secrets.scrub(cleaned);
        return result.timedOut() ? "下载超时；" + safe : safe;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}
