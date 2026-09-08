"""Build shared Compose contours from the approved day reference.
Run with Pillow, numpy and vtracer; no bitmap is shipped to the production renderer.
"""
from pathlib import Path
from collections import deque
import xml.etree.ElementTree as ET
import shutil
import numpy as np
from PIL import Image, ImageDraw
import vtracer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/whale-static-review'
OUT.mkdir(parents=True, exist_ok=True)
reference = ROOT / 'docs/miffan/whale-girl/line-art/reference-day.png'
fixture = ROOT / 'app/src/androidTest/assets/whale-review/reference-day.png'
fixture.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(reference, fixture)
a = np.asarray(Image.open(ROOT / 'docs/miffan/whale-girl/line-art/reference-day.png').convert('RGB'))[:650, :672].astype(int)
mask = (a[:,:,2]-a[:,:,0]>20) & (a[:,:,2]-a[:,:,1]>6) & (a[:,:,0]<240)
# Remove the adjacent expression entering the right edge of the reference sheet.
seen = set()
for y in range(650):
    if not mask[y,671] or (y,671) in seen: continue
    q=deque([(y,671)])
    while q:
        yy,xx=q.popleft()
        if (yy,xx) in seen or not (0<=yy<650 and 0<=xx<672) or not mask[yy,xx]: continue
        seen.add((yy,xx)); mask[yy,xx]=False
        q.extend((yy+dy,xx+dx) for dy in [-1,0,1] for dx in [-1,0,1])
# Keep the lashes, but redraw the lower iris boundary as a smooth curve rather
# than thresholding through the reference's pale lower highlight. One top glint.
def cubic(points, count=28):
    p0,p1,p2,p3=map(np.array,points)
    return [tuple((1-t)**3*p0+3*(1-t)**2*t*p1+3*(1-t)*t*t*p2+t**3*p3)
            for t in np.linspace(0,1,count)]
for box, curves, glint in [
    ((220,464,275,520),[
        [(246,450),(261,450),(271,465),(271,484)],
        [(271,484),(272,503),(262,516),(249,516)],
        [(249,516),(233,516),(224,503),(224,484)],
        [(224,484),(223,466),(231,452),(246,450)]],(237,464)),
    ((365,446,420,503),[
        [(386,426),(404,426),(417,442),(417,461)],
        [(417,461),(418,482),(407,498),(392,498)],
        [(392,498),(376,498),(367,484),(367,464)],
        [(367,464),(365,445),(374,428),(386,426)]],(380,443)),
]:
    before=mask.copy()
    shape=Image.new('1',(672,650))
    ImageDraw.Draw(shape).polygon([p for c in curves for p in cubic(c)],fill=1)
    iris=np.asarray(shape)
    x0,y0,x1,y1=box
    mask[y0:y1,x0:x1]=iris[y0:y1,x0:x1]
    # Fill all old tiny glints in the upper portion inside the new iris.
    mask |= iris
    q=deque([(glint[1],glint[0])]); visited=set()
    while q:
        y,x=q.popleft()
        if (y,x) in visited or abs(x-glint[0])>13 or abs(y-glint[1])>13 or before[y,x]: continue
        visited.add((y,x)); mask[y,x]=False
        q.extend([(y-1,x),(y+1,x),(y,x-1),(y,x+1)])
Image.fromarray(np.where(mask,0,255).astype('uint8')).save(OUT/'contour-input.png')
vtracer.convert_image_to_svg_py(str(OUT/'contour-input.png'),str(OUT/'contour.svg'),
    colormode='binary',mode='spline',filter_speckle=3,corner_threshold=65,
    length_threshold=3.5,max_iterations=10,splice_threshold=45,path_precision=2)
svg=ET.parse(OUT/'contour.svg').getroot()
paths=[]
for elem in svg.iter():
    if elem.tag.endswith('path'):
        paths.append((elem.attrib['d'],elem.attrib.get('transform','')))
print('Paths:',len(paths),'characters:',sum(len(p[0]) for p in paths))
# Keep each contour separate so no Kotlin constant exceeds the JVM UTF-8 limit.
entries=[]
for d,t in paths:
    import re
    match=re.fullmatch(r'translate\(([-\d.]+)[ ,]([-\d.]+)\)',t)
    tx,ty=match.groups() if match else ('0','0')
    if t and not match: raise ValueError(t)
    entries.append(f'        contour("{d}", {tx}f, {ty}f),')
outline_data, outline_transform = max(paths, key=lambda p: len(p[0]))
outline_data = outline_data.split('Z')[0] + 'Z'
m = re.fullmatch(r'translate\(([-\d.]+)[ ,]([-\d.]+)\)', outline_transform)
ox, oy = m.groups()
outline_entry = f'    val silhouette = contour("{outline_data}", {ox}f, {oy}f)\n'
code='''package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform

/** Approved static reference view; production shares these exact cached contours. */
@Composable
internal fun WhaleGirlStaticReview(modifier: Modifier = Modifier, dark: Boolean = false) {
    val palette = if (dark) WhaleLinePalette.Night else WhaleLinePalette.Day
    Canvas(modifier) {
        drawRect(palette.paper)
        val unit = minOf(size.width / 672f, size.height / 650f)
        withTransform({
            translate((size.width - 672f * unit) / 2, (size.height - 650f * unit) / 2)
            scale(unit, unit, Offset.Zero)
        }) {
            drawWhaleColorBlocks(palette)
            StaticWhaleContours.paths.forEach { drawWhaleContour(it, palette.ink, unit) }
        }
    }
}

/** Generated from approved reference contours by tools/whale-line-art/trace_static_face.py.
 * Coordinates preserve the reference stroke silhouette, including tapered hair and eyelashes.
 */
internal object StaticWhaleContours {
    private fun contour(data: String, x: Float, y: Float): Path =
        PathParser().parsePathString(data).toPath().apply { translate(Offset(x, y)) }
'''+ outline_entry + '''    val paths = listOf(
'''+ '\n'.join(entries)+'''
    )
}
'''
(ROOT/'app/src/main/java/me/ayuilos/miffan/ui/components/ui/WhaleGirlStaticReview.kt').write_text(code)
