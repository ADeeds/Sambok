#!/usr/bin/env python3
"""Rewrite CSS keyframe animations as SMIL, for renderers that predate CSS on SVG.

Android 4.4's WebView is Chromium 30, which renders SVG but ignores CSS `transform` animations on
SVG elements — the artwork draws and nothing moves. SMIL (`<animate>`) has been supported there
since forever, and pausing is one call (`svg.pauseAnimations()`), which is exactly what the screen
needs.

Each animated element is wrapped in a `<g>` that carries the animation, so the element keeps its
own `transform` attribute. A CSS `transform-origin: cx cy` is reproduced as
`translate(cx,cy) T translate(-cx,-cy)`, which is what the origin means.

Usage:
    python3 tools/svg_to_smil.py in.svg out.svg
"""
import re
import sys
import xml.etree.ElementTree as ET

SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)


def parse_keyframes(style):
    frames = {}
    for m in re.finditer(r"@keyframes\s+(\w+)\s*\{(.*?)\n    \}", style, re.S):
        stops = []
        for block in re.finditer(r"([\d.]+%)(?:\s*,\s*([\d.]+%))?\s*\{([^}]*)\}", m.group(2)):
            decl = block.group(3)
            props = dict(re.findall(r"(transform|opacity|stroke-dashoffset)\s*:\s*([^;]+);", decl))
            stops.append((float(block.group(1).rstrip("%")), props))
        frames[m.group(1)] = stops
    return frames


def parse_rules(style):
    """class -> (keyframes name, duration, begin, transform-origin)."""
    rules = []
    for m in re.finditer(r"\.([\w-]+)\s*\{([^}]*)\}", style):
        cls, body = m.group(1), m.group(2)
        anim = re.search(r"animation\s*:\s*([^;]+);", body)
        if not anim:
            continue
        tokens = anim.group(1).replace(",", " ").split()
        name = tokens[0]
        dur = next((t for t in tokens if re.fullmatch(r"[\d.]+s", t)), None)
        begin = next((t for t in tokens if re.fullmatch(r"-[\d.]+s", t)), "0s")
        if not dur:
            continue
        origin = None
        o = re.search(r"transform-origin\s*:\s*(-?[\d.]+)px\s+(-?[\d.]+)px", body)
        if o:
            origin = (float(o.group(1)), float(o.group(2)))
        rules.append((cls, name, dur, begin, origin))
    return rules


def svg_transform(css):
    out = []
    for fn, args in re.findall(r"(\w+)\(([^)]*)\)", css):
        a = [x.strip() for x in args.split(",") if x.strip()]
        n = lambda v: re.sub(r"(px|deg|rad|%)$", "", v.strip())
        if fn == "rotate":
            out.append("rotate(%s)" % n(a[0]))
        elif fn == "translate":
            out.append("translate(%s,%s)" % (n(a[0]), n(a[1]) if len(a) > 1 else "0"))
        elif fn == "translateX":
            out.append("translate(%s,0)" % n(a[0]))
        elif fn == "translateY":
            out.append("translate(0,%s)" % n(a[0]))
        elif fn == "scale":
            out.append("scale(%s,%s)" % (n(a[0]), n(a[1]) if len(a) > 1 else n(a[0])))
        elif fn == "scaleX":
            out.append("scale(%s,1)" % n(a[0]))
        elif fn == "scaleY":
            out.append("scale(1,%s)" % n(a[0]))
        elif fn in ("skewX", "skewY"):
            out.append("%s(%s)" % (fn, n(a[0])))
    return " ".join(out)


def series(stops, prop, origin):
    times, values = [], []
    for pct, props in stops:
        if prop not in props:
            continue
        if prop == "transform":
            v = svg_transform(props[prop])
            if origin:
                cx, cy = origin
                v = "translate(%s,%s) %s translate(%s,%s)" % (cx, cy, v, -cx, -cy)
        else:
            v = props[prop].strip()
        times.append(pct / 100.0)
        values.append(v)
    return times, values


def add_animate(parent, attribute, times, values, dur, begin, spline):
    if len(values) < 2:
        return
    el = ET.SubElement(parent, "{%s}animate" % SVG)
    el.set("attributeName", attribute)
    el.set("values", ";".join(values))
    el.set("keyTimes", ";".join("%.4f" % t for t in times))
    el.set("dur", dur)
    el.set("repeatCount", "indefinite")
    el.set("begin", begin)
    el.set("calcMode", "spline" if spline else "linear")
    if spline:
        el.set("keySplines", ";".join(["0.42 0 0.58 1"] * (len(values) - 1)))


def main(src_path, dst_path):
    tree = ET.parse(src_path)
    root = tree.getroot()

    style_el = next((e for e in root.iter() if e.tag == "{%s}style" % SVG), None)
    if style_el is None:
        raise SystemExit("no <style> element")
    style_text = style_el.text or ""

    frames = parse_keyframes(style_text)
    rules = parse_rules(style_text)

    index = {}
    for parent in root.iter():
        for child in list(parent):
            index[child] = parent

    wrapped = 0
    for cls, name, dur, begin, origin in rules:
        stops = frames.get(name, [])
        if not stops:
            continue
        for el in [e for e in root.iter() if cls in (e.get("class") or "").split()]:
            parent = index.get(el)
            if parent is None:
                continue
            pos = list(parent).index(el)
            wrapper = ET.Element("{%s}g" % SVG)
            wrapper.set("class", "smil-" + cls)
            parent.insert(pos, wrapper)
            parent.remove(el)
            wrapper.append(el)
            index[el] = wrapper
            index[wrapper] = parent
            for prop, attr in (("transform", "transform"), ("opacity", "opacity"),
                               ("stroke-dashoffset", "stroke-dashoffset")):
                times, values = series(stops, prop, origin)
                add_animate(wrapper, attr, times, values, dur, begin,
                            spline=(prop == "transform"))
            wrapped += 1

    # CSS motion is now SMIL's job; leaving it would double up in a modern browser.
    new_style = re.sub(r"@keyframes\s+\w+\s*\{.*?\n    \}", "", style_text, flags=re.S)
    new_style = re.sub(r"animation\s*:[^;]*;", "", new_style)
    style_el.text = new_style

    tree.write(dst_path, encoding="utf-8", xml_declaration=False)
    print("svg_to_smil: %d element(s) animated, wrote %s" % (wrapped, dst_path))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
