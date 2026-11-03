package com.videostudio.ocr;

import java.nio.file.Path;

/** 画面文字识别端口 */
public interface TextRecognizer {

    /** 识别一张图片中的文字；失败返回空字符串而不抛异常（单帧 OCR 失败不应中断整条流水线） */
    String recognize(Path image);

    /** 引擎标识，写入证据的 source 字段 */
    String engine();
}
