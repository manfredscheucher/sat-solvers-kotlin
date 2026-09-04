// Shared Typst template for ksat-ports documentation.
#let ks-accent = rgb("#5B2A86")
#let ks-paper = rgb("#FBFBF9")
#let ks-ink = rgb("#222222")
#let ks-muted = rgb("#6B6B6B")

#let note(title: "Note", body) = block(
  fill: rgb("#EDE7F3"), stroke: (left: 3pt + ks-accent), inset: 10pt, radius: 3pt, width: 100%,
)[ #text(weight: "bold", fill: ks-accent)[#title] \ #body ]

#let warn(body) = block(
  fill: rgb("#FBEFE8"), stroke: (left: 3pt + rgb("#C2410C")), inset: 10pt, radius: 3pt, width: 100%,
)[ #text(weight: "bold", fill: rgb("#C2410C"))[Caution] \ #body ]

#let lesson(body) = block(
  fill: rgb("#E7F3EA"), stroke: (left: 3pt + rgb("#2E7D32")), inset: 10pt, radius: 3pt, width: 100%,
)[ #text(weight: "bold", fill: rgb("#2E7D32"))[Lesson] \ #body ]

#let ks-doc(title: "", subtitle: none, version: none, body) = {
  set page(paper: "a4", margin: 2.2cm, fill: ks-paper)
  set text(font: ("Helvetica Neue", "Arial"), size: 10.5pt, fill: ks-ink)
  set par(justify: true, leading: 0.62em)
  show heading.where(level: 1): it => block(above: 20pt, below: 10pt)[ #set text(size: 18pt, fill: ks-accent, weight: "bold"); #it ]
  show heading.where(level: 2): it => block(above: 16pt, below: 8pt)[ #set text(size: 14pt, fill: ks-ink, weight: "bold"); #it ]
  show heading.where(level: 3): it => block(above: 12pt, below: 6pt)[ #set text(size: 11.5pt, fill: ks-ink, weight: "bold"); #it ]
  show raw.where(block: true): it => block(fill: rgb("#F2F1EC"), inset: 9pt, radius: 3pt, width: 100%)[#it]
  show link: it => text(fill: ks-accent)[#it]
  v(6cm)
  align(center)[ #text(size: 28pt, weight: "bold", fill: ks-accent)[#title] \ #v(0.4cm)
    #if subtitle != none { text(size: 14pt, fill: ks-muted)[#subtitle] } #v(0.8cm)
    #if version != none { text(size: 11pt, fill: ks-muted)[#version] } ]
  v(1fr); align(center)[ #text(size: 9pt, fill: ks-muted)[ksat-ports · Kotlin SAT solver ports] ]
  pagebreak(); outline(indent: auto, depth: 3); pagebreak(); body
}
