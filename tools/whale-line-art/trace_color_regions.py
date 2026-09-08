"""Trace flat color regions inside the approved contour. Run after trace_static_face.py.
Pillow closes subpixel line gaps for segmentation; runtime output is cached Compose paths.
"""
from pathlib import Path
from collections import deque
from PIL import Image, ImageFilter
import xml.etree.ElementTree as ET
import re
import vtracer
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/whale-static-review'
im=Image.open(OUT/'contour-input.png').convert('L').filter(ImageFilter.MinFilter(5))
w,h=im.size; p=im.load(); seen=set(); regions=[]
for y in range(h):
 for x in range(w):
  if p[x,y]<128 or (x,y) in seen: continue
  q=deque([(x,y)]); seen.add((x,y)); pts=[]
  while q:
   a,b=q.popleft(); pts.append((a,b))
   for c,e in ((a-1,b),(a+1,b),(a,b-1),(a,b+1)):
    if 0<=c<w and 0<=e<h and p[c,e]>128 and (c,e) not in seen:
     seen.add((c,e)); q.append((c,e))
  if len(pts)>80: regions.append(pts)
# Region numbering is deterministic for the versioned approved contour input.
groups={'hair':[1,5,21,24,25,26,27], 'fin':[12,19], 'accent':[10,11,17,23],
        'frill':[2,3,4,6,7,8,9], 'paper':[13,14,15,16,18]}
code=['package me.ayuilos.miffan.ui.components.ui\n',
'import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Path\nimport androidx.compose.ui.graphics.vector.PathParser\nimport androidx.compose.ui.graphics.drawscope.DrawScope\n',
'/** Generated flat regions inside the approved contours; no runtime bitmap. */\ninternal object WhaleColorRegions {',
'    private fun part(d: String, x: Float, y: Float): Path = PathParser().parsePathString(d).toPath().apply { translate(Offset(x, y)) }']
for name,ids in groups.items():
 m=Image.new('L',(w,h)); pix=m.load()
 for i in ids:
  for point in regions[i]: pix[point[0],point[1]]=255
 m=m.filter(ImageFilter.MaxFilter(7)); m=Image.eval(m,lambda x:255-x)
 m.save(OUT/f'color-{name}.png')
 vtracer.convert_image_to_svg_py(str(OUT/f'color-{name}.png'),str(OUT/f'color-{name}.svg'),colormode='binary',mode='spline',filter_speckle=3,path_precision=2)
 code.append(f'    val {name} = listOf(')
 for elem in ET.parse(OUT/f'color-{name}.svg').getroot().iter():
  if not elem.tag.endswith('path'): continue
  match=re.fullmatch(r'translate\(([-\d.]+)[ ,]([-\d.]+)\)',elem.attrib.get('transform',''))
  x,y=match.groups() if match else ('0','0')
  code.append(f'        part("{elem.attrib["d"]}", {x}f, {y}f),')
 code.append('    )')
code.append('}\n\ninternal fun DrawScope.drawWhaleColorBlocks(palette: WhaleLinePalette) {')
code.append('    drawPath(StaticWhaleContours.silhouette, palette.paper)')
for name in groups: code.append(f'    WhaleColorRegions.{name}.forEach {{ drawPath(it, palette.{name}) }}')
code.append('}\n')
(ROOT/'app/src/main/java/me/ayuilos/miffan/ui/components/ui/WhaleGirlColorRegions.kt').write_text('\n'.join(code))
