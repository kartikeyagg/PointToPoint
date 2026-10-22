// Captures the main display with ScreenCaptureKit and streams changed regions to stdout.
// Usage: screenstream [--fps N]
// Frame: little-endian uint32 magic 'SSF1', width, height, rectCount, then per rect
// x, y, w, h followed by w*h*4 bytes of BGRA (rows top to bottom, tightly packed).

import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

let magic: UInt32 = 0x3146_5353  // "SSF1"
let maxRects = 64

func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

var fps = 30
var args = CommandLine.arguments.dropFirst()
while let arg = args.popFirst() {
    if arg == "--fps", let value = args.popFirst(), let n = Int(value), n > 0 {
        fps = n
    } else {
        fail("usage: screenstream [--fps N]")
    }
}

signal(SIGPIPE, SIG_IGN)

final class Streamer: NSObject, SCStreamOutput, SCStreamDelegate {
    private var lastWidth = 0
    private var lastHeight = 0
    private var out = [UInt8]()

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sb.isValid else { return }
        let info = (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]])?.first
        if let raw = info?[.status] as? Int, SCFrameStatus(rawValue: raw) != .complete { return }
        guard let pixels = CMSampleBufferGetImageBuffer(sb) else { return }

        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { return }
        let width = CVPixelBufferGetWidth(pixels)
        let height = CVPixelBufferGetHeight(pixels)
        let stride = CVPixelBufferGetBytesPerRow(pixels)

        let full = CGRect(x: 0, y: 0, width: width, height: height)
        var rects = [CGRect]()
        if width == lastWidth && height == lastHeight {
            let dirty = info?[.dirtyRects] as? [[String: Any]] ?? []
            for entry in dirty {
                if let rect = CGRect(dictionaryRepresentation: entry as CFDictionary) {
                    let clipped = rect.integral.intersection(full)
                    if !clipped.isNull && clipped.width >= 1 && clipped.height >= 1 { rects.append(clipped) }
                }
            }
            if dirty.isEmpty { rects = [full] }  // no dirty info: send everything
        } else {
            rects = [full]
        }
        if rects.count > maxRects { rects = [rects.dropFirst().reduce(rects[0]) { $0.union($1) }] }
        lastWidth = width
        lastHeight = height

        out.removeAll(keepingCapacity: true)
        put(magic); put(UInt32(width)); put(UInt32(height)); put(UInt32(rects.count))
        for r in rects {
            let x = Int(r.minX), y = Int(r.minY), w = Int(r.width), h = Int(r.height)
            put(UInt32(x)); put(UInt32(y)); put(UInt32(w)); put(UInt32(h))
            for row in y..<(y + h) {
                out.append(contentsOf: UnsafeRawBufferPointer(start: base + row * stride + x * 4, count: w * 4))
            }
        }
        let ok = out.withUnsafeBufferPointer { buf -> Bool in
            fwrite(buf.baseAddress, 1, buf.count, stdout) == buf.count && fflush(stdout) == 0
        }
        if !ok { exit(0) }  // stdout closed
    }

    private func put(_ v: UInt32) {
        out.append(UInt8(v & 0xff)); out.append(UInt8((v >> 8) & 0xff))
        out.append(UInt8((v >> 16) & 0xff)); out.append(UInt8(v >> 24))
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        fail("stream stopped: \(error.localizedDescription)")
    }
}

if !CGPreflightScreenCaptureAccess() {
    CGRequestScreenCaptureAccess()
    fail("Screen Recording permission is not granted", code: 2)
}

// Exit when the parent closes our stdin.
Thread.detachNewThread {
    var byte = [UInt8](repeating: 0, count: 256)
    while read(0, &byte, byte.count) > 0 {}
    exit(0)
}

let streamer = Streamer()
var keepAlive: SCStream?

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
            fail("no display found")
        }
        let config = SCStreamConfiguration()
        config.width = display.width  // points; SCK scales Retina on the GPU
        config.height = display.height
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = true
        config.queueDepth = 3
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: config, delegate: streamer)
        try stream.addStreamOutput(streamer, type: .screen, sampleHandlerQueue: DispatchQueue(label: "frames"))
        try await stream.startCapture()
        keepAlive = stream
    } catch {
        let code = (error as NSError).code
        fail("capture failed: \(error.localizedDescription)", code: code == -3801 ? 2 : 1)
    }
}
dispatchMain()
