package com.videostudio.asr;

import java.nio.file.Path;

/** 语音识别端口：云端 API 与本地 whisper 都实现它 */
public interface SpeechRecognizer {

    /** 识别单个音频文件 */
    Result transcribe(Path audio);

    /** 引擎标识，写入证据的 source 字段 */
    String engine();

    /**
     * @param text  识别出的纯文本
     * @param words 分段时间戳（相对传入音频文件），用于映射回全局时间轴
     * @param engine 实际执行引擎
     */
    record Result(String text, java.util.List<Word> words, String engine) {
        public boolean blank() {
            return text == null || text.isBlank();
        }
    }

    /** 带时间的识别片段；startMs/endMs 相对传入的音频文件 */
    record Word(long startMs, long endMs, String text) {
    }
}
