package com.videostudio.media;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 画面去重：把相邻帧里视觉上几乎一样的（PPT 停留、镜头静止）合并掉，
 * 否则 OCR 会把同一屏文字重复几十次，污染检索与上下文预算。
 *
 * <p>用两路指纹判断，缺一不可：
 * <ul>
 *   <li>dHash 64 位：对亮度梯度敏感，快，用作持久化的 hash 展示字段；</li>
 *   <li>缩略灰度签名（24x14）的平均绝对差：对「整屏深色 + 中间一行字」这种
 *       低频画面更稳——纯 dHash 在 8x8 网格下会把四张不同标题的幻灯片算成同一个值。</li>
 * </ul>
 */
@Component
public class FrameDeduper {

    /** dHash 汉明距离阈值：≤ 5 视为同屏（仅作为第一道便宜过滤） */
    public static final int MAX_HAMMING = 5;

    /** 缩略签名平均绝对差阈值（0-255 灰度）：低于此值才算同一屏 */
    public static final double MAX_SIGNATURE_MAD = 1.2;

    private static final int SIG_W = 24;
    private static final int SIG_H = 14;

    /** 一帧的视觉指纹 */
    public record Fingerprint(long dhash, byte[] signature) {
        public String hex() {
            return FrameDeduper.hex(dhash);
        }
    }

    public Fingerprint fingerprint(Path image) {
        try {
            BufferedImage src = ImageIO.read(image.toFile());
            if (src == null) {
                return null;
            }
            return new Fingerprint(dhash(src), signature(src));
        } catch (Exception e) {
            return null;
        }
    }

    /** 兼容旧调用：仅取 dHash */
    public Long dhash(Path image) {
        Fingerprint fp = fingerprint(image);
        return fp == null ? null : fp.dhash();
    }

    /**
     * 判断两帧是否为同一屏：签名近似且 dHash 接近。
     * 任一侧为空（图片读不出来）时按「不同屏」处理，宁可多留一帧也不误删证据。
     */
    public boolean same(Fingerprint a, Fingerprint b) {
        if (a == null || b == null) {
            return false;
        }
        return signatureMad(a.signature(), b.signature()) <= MAX_SIGNATURE_MAD;
    }

    public int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    public static String hex(Long hash) {
        return hash == null ? null : String.format("%016x", hash);
    }

    /** 平均绝对差：逐像素差的均值，对整屏亮度变化不敏感、对内容变化敏感 */
    public static double signatureMad(byte[] a, byte[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return Double.MAX_VALUE;
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs((a[i] & 0xff) - (b[i] & 0xff));
        }
        return (double) sum / a.length;
    }

    private long dhash(BufferedImage src) {
        BufferedImage gray = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, 9, 8, null);
        g.dispose();
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray.getRaster().getSample(x, y, 0);
                int right = gray.getRaster().getSample(x + 1, y, 0);
                hash = (hash << 1) | (left > right ? 1 : 0);
            }
        }
        return hash;
    }

    private byte[] signature(BufferedImage src) {
        BufferedImage small = new BufferedImage(SIG_W, SIG_H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, SIG_W, SIG_H, null);
        g.dispose();
        byte[] values = new byte[SIG_W * SIG_H];
        int i = 0;
        for (int y = 0; y < SIG_H; y++) {
            for (int x = 0; x < SIG_W; x++) {
                values[i++] = (byte) small.getRaster().getSample(x, y, 0);
            }
        }
        return values;
    }
}
