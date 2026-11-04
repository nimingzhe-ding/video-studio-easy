package com.videostudio.asr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 极简 WAV 读取器：只处理 {@link com.videostudio.media.AudioExtractor} 产出的
 * 16kHz 单声道 PCM16 小端格式。
 *
 * <p>之所以不用 Java Sound 的 AudioSystem：它依赖系统混音器，在无图形界面的服务端
 * 会因为没有可用的音频设备而报错；而这里只需要按 RIFF 结构把采样点取出来。
 *
 * <p>多声道文件按第一声道取值即可——演讲、会议录音左右声道内容相同，做声学特征
 * 不需要混音；混音反而会引入相位抵消，让基频估计变差。
 */
public record WavAudio(short[] samples, int sampleRate) {

    public static WavAudio read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 44) {
            throw new IOException("WAV 文件太小，无法解析: " + file);
        }
        if (bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F') {
            throw new IOException("不是有效的 RIFF/WAV 文件: " + file);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 1;
        int sampleRate = 16000;
        int bits = 16;
        int dataOffset = -1;
        int dataLength = 0;

        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            int size = buffer.getInt(pos + 4);
            int body = pos + 8;
            if (size < 0) {
                break;
            }
            if ("fmt ".equals(id) && body + 16 <= bytes.length) {
                channels = Math.max(1, buffer.getShort(body + 2) & 0xFFFF);
                sampleRate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14) & 0xFFFF;
            } else if ("data".equals(id)) {
                dataOffset = body;
                dataLength = Math.min(size, Math.max(0, bytes.length - body));
                break;
            }
            // RIFF 规定块长度为奇数时补一个填充字节
            pos = body + size + (size % 2);
        }

        if (dataOffset < 0 || dataLength <= 0) {
            throw new IOException("WAV 中没有音频数据块: " + file);
        }
        if (bits != 16) {
            throw new IOException("只支持 16 位 PCM，当前为 " + bits + " 位: " + file);
        }
        if (sampleRate <= 0) {
            throw new IOException("WAV 采样率非法: " + sampleRate);
        }

        int frames = dataLength / 2;
        short[] samples = new short[frames / channels];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort(dataOffset + i * channels * 2);
        }
        return new WavAudio(samples, sampleRate);
    }
}
