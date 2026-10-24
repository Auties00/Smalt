"""Writes the SSA, ASS, TTML, SAMI, LRC and MicroDVD samples of the I/O behavior harness
(core/src/test/resources/samples/subtitle), small hand-written files with unsorted, overlapping and styled cues.

    python -I tools/testdata/behavior/make-subtitle-samples.py [output directory]
"""
import os
import sys

ASS = """[Script Info]
; I/O behavior sample
ScriptType: v4.00+
PlayResX: 384
PlayResY: 288

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
Style: Top,Arial,18,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:00.90,Default,,0,0,0,,{\\i1}World{\\i0}
Dialogue: 0,0:00:00.00,0:00:00.40,Default,,0,0,0,,Hello
Dialogue: 1,0:00:00.20,0:00:01.00,Top,Sign,0,0,0,,{\\pos(192,30)}Overlap
Dialogue: 0,0:00:01.25,0:00:02.00,Default,Bob,0,0,0,,Two\\Nlines
"""

SSA = """[Script Info]
ScriptType: v4.00

[V4 Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
Style: Default,Arial,20,16777215,255,0,0,0,0,1,2,0,2,10,10,10,0,0

[Events]
Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: Marked=0,0:00:00.00,0:00:00.40,Default,,0000,0000,0000,,Hello
Dialogue: Marked=0,0:00:00.50,0:00:00.90,Default,,0000,0000,0000,,{\\a6}World
Dialogue: Marked=0,0:00:01.25,0:00:02.00,Default,,0000,0000,0000,,Two\\Nlines
"""

TTML = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" xml:lang="en">
  <head>
    <layout>
      <region xml:id="bottom" tts:origin="10% 10%" tts:extent="80% 80%" tts:displayAlign="after" tts:textAlign="center"/>
    </layout>
  </head>
  <body region="bottom">
    <div>
      <p begin="00:00:00.000" end="00:00:00.400">Hello</p>
      <p begin="0.5s" end="0.9s"><span tts:fontStyle="italic">World</span></p>
      <p begin="1.25s" end="2s">Two<br/>lines</p>
    </div>
  </body>
</tt>
"""

SAMI = """<SAMI>
<HEAD>
<STYLE TYPE="text/css">
<!--
.ENCC { Name: English; lang: en-US; }
.FRCC { Name: French; lang: fr-FR; }
-->
</STYLE>
</HEAD>
<BODY>
<SYNC Start=0><P Class=ENCC>Hello<P Class=FRCC>Bonjour
<SYNC Start=400><P Class=ENCC>&nbsp;<P Class=FRCC>&nbsp;
<SYNC Start=500><P Class=ENCC><i>World</i><P Class=FRCC><i>Monde</i>
<SYNC Start=900><P Class=ENCC>&nbsp;<P Class=FRCC>&nbsp;
<SYNC Start=1250><P Class=ENCC>Two<br>lines<P Class=FRCC>Deux<br>lignes
<SYNC Start=2000><P Class=ENCC>&nbsp;<P Class=FRCC>&nbsp;
</BODY>
</SAMI>
"""

LRC = """[ti:Behavior]
[ar:Smalt]
[00:00.00]Hello
[00:00.40]
[00:00.50]<00:00.50>World <00:00.70>again
[00:00.90]
[00:01.25]Two
[00:01.25]lines
[00:02.00]
"""

MICRODVD = """{1}{1}25
{0}{10}Hello
{12}{22}{y:i}World
{31}{50}Two|lines
"""


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'core/src/test/resources/samples'
    directory = os.path.join(out, 'subtitle')
    os.makedirs(directory, exist_ok=True)
    for name, text in (('cues.ass', ASS), ('cues.ssa', SSA), ('cues.ttml', TTML), ('cues.smi', SAMI),
                       ('cues.lrc', LRC), ('cues.sub', MICRODVD)):
        with open(os.path.join(directory, name), 'w', encoding='utf-8', newline='\n') as sink:
            sink.write(text)


if __name__ == '__main__':
    main()
