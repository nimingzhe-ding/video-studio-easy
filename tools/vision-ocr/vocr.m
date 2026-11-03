// vocr — 基于 macOS Vision 框架的本地 OCR 小工具（无需安装 tesseract）
//
// 构建（Objective-C，兼容 Xcode CommandLineTools）：
//   clang -fobjc-arc -O2 -isysroot "$(xcrun --show-sdk-path)" -target arm64-apple-macos11.0 \
//         -framework Foundation -framework Vision -framework CoreGraphics -framework ImageIO \
//         -o vocr vocr.m
// 用法：
//   ./vocr /path/to/frame.jpg [zh-Hans|en-US]
// 输出：逐行识别文本到 stdout；错误信息写 stderr，退出码非 0 表示失败。

#import <Foundation/Foundation.h>
#import <Vision/Vision.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>

static CGImageRef load_image(const char *path) {
    NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:path]];
    CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)url, NULL);
    if (!source) {
        return NULL;
    }
    CGImageRef image = CGImageSourceCreateImageAtIndex(source, 0, NULL);
    CFRelease(source);
    return image;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc < 2) {
            fprintf(stderr, "usage: vocr <image> [lang]\n");
            return 2;
        }
        NSString *lang = argc >= 3 ? [NSString stringWithUTF8String:argv[2]] : @"zh-Hans";
        CGImageRef image = load_image(argv[1]);
        if (!image) {
            fprintf(stderr, "cannot read image: %s\n", argv[1]);
            return 1;
        }

        VNRecognizeTextRequest *request = [[VNRecognizeTextRequest alloc] init];
        request.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
        request.usesLanguageCorrection = YES;
        if (@available(macOS 13.0, *)) {
            request.automaticallyDetectsLanguage = YES;
        }
        NSMutableArray<NSString *> *languages = [NSMutableArray array];
        if (![lang isEqualToString:@"auto"]) {
            [languages addObject:lang];
        }
        [languages addObject:@"zh-Hans"];
        [languages addObject:@"en-US"];
        request.recognitionLanguages = languages;

        VNImageRequestHandler *handler =
            [[VNImageRequestHandler alloc] initWithCGImage:image options:@{}];
        NSError *error = nil;
        if (![handler performRequests:@[ request ] error:&error]) {
            fprintf(stderr, "ocr failed: %s\n", error.localizedDescription.UTF8String);
            CGImageRelease(image);
            return 1;
        }
        CGImageRelease(image);

        NSMutableArray<NSString *> *lines = [NSMutableArray array];
        for (VNRecognizedTextObservation *observation in request.results) {
            VNRecognizedText *candidate = [[observation topCandidates:1] firstObject];
            if (!candidate) {
                continue;
            }
            NSString *text = [candidate.string
                stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
            if (text.length > 0) {
                [lines addObject:text];
            }
        }
        printf("%s\n", [[lines componentsJoinedByString:@"\n"] UTF8String]);
    }
    return 0;
}
