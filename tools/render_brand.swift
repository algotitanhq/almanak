// Renders the Leanback banner and launcher icons from the bundled Fraunces font.
// Usage: swift tools/render_brand.swift   (run from repo root)
import AppKit
import CoreText

let fontURL = URL(fileURLWithPath: "app/src/main/res/font/fraunces.ttf")
let descs = CTFontManagerCreateFontDescriptorsFromURL(fontURL as CFURL) as! [CTFontDescriptor]

func fraunces(_ size: CGFloat, weight: Double) -> CTFont {
    let tag: (String) -> Int = { s in s.unicodeScalars.reduce(0) { ($0 << 8) | Int($1.value) } }
    let attrs: [CFString: Any] = [kCTFontVariationAttribute: [tag("wght"): weight, tag("opsz"): 144.0, tag("SOFT"): 50.0]]
    return CTFontCreateWithFontDescriptor(CTFontDescriptorCreateCopyWithAttributes(descs[0], attrs as CFDictionary), size, nil)
}

let ink = CGColor(red: 0.055, green: 0.051, blue: 0.043, alpha: 1)
let paper = CGColor(red: 0.949, green: 0.929, blue: 0.894, alpha: 1)
let ember = CGColor(red: 0.91, green: 0.62, blue: 0.36, alpha: 1)

func render(_ w: Int, _ h: Int, to path: String, draw: (CGContext) -> Void) {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    draw(ctx)
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
    print("wrote \(path)")
}

func text(_ s: String, _ font: CTFont, _ color: CGColor, x: CGFloat, y: CGFloat, ctx: CGContext) {
    let str = NSAttributedString(string: s, attributes: [.init(kCTFontAttributeName as String): font, .init(kCTForegroundColorAttributeName as String): color])
    let line = CTLineCreateWithAttributedString(str)
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(line, ctx)
}

func width(_ s: String, _ font: CTFont) -> CGFloat {
    let str = NSAttributedString(string: s, attributes: [.init(kCTFontAttributeName as String): font])
    return CTLineGetTypographicBounds(CTLineCreateWithAttributedString(str), nil, nil, nil)
}

/// The mark: a horizon line with a rising sun arc.
func mark(ctx: CGContext, cx: CGFloat, cy: CGFloat, r: CGFloat, stroke: CGFloat) {
    ctx.setStrokeColor(ember); ctx.setLineWidth(stroke); ctx.setLineCap(.round)
    ctx.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: 0, endAngle: .pi, clockwise: false)
    ctx.strokePath()
    ctx.setStrokeColor(paper)
    ctx.move(to: CGPoint(x: cx - r * 1.5, y: cy)); ctx.addLine(to: CGPoint(x: cx + r * 1.5, y: cy)); ctx.strokePath()
}

// Banner: 320x180 dp @ xhdpi = 640x360 px
render(640, 360, to: "app/src/main/res/drawable-xhdpi/banner.png") { ctx in
    ctx.setFillColor(ink); ctx.fill(CGRect(x: 0, y: 0, width: 640, height: 360))
    let f = fraunces(112, weight: 300)
    let word = "Almanak"
    let w = width(word, f)
    let x = (640 - w) / 2
    text(word, f, paper, x: x, y: 160, ctx: ctx)
    mark(ctx: ctx, cx: 320, cy: 104, r: 26, stroke: 4)
}

// Launcher icons: 48 dp
for (dir, px) in [("mipmap-xhdpi", 96), ("mipmap-xxxhdpi", 192)] {
    render(px, px, to: "app/src/main/res/\(dir)/ic_launcher.png") { ctx in
        let s = CGFloat(px)
        ctx.setFillColor(ink)
        ctx.addPath(CGPath(roundedRect: CGRect(x: 0, y: 0, width: s, height: s), cornerWidth: s * 0.22, cornerHeight: s * 0.22, transform: nil))
        ctx.fillPath()
        let f = fraunces(s * 0.62, weight: 400)
        text("A", f, paper, x: (s - width("A", f)) / 2, y: s * 0.30, ctx: ctx)
        mark(ctx: ctx, cx: s / 2, cy: s * 0.20, r: s * 0.09, stroke: max(2, s * 0.025))
    }
}
