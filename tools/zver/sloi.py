import sys, json
from PIL import Image, ImageDraw, ImageFilter
import numpy as np
from scipy import ndimage as nd
S=sys.argv[1]
src=Image.open(S+"/zlico/vyrez.png"); a=np.asarray(src).astype(float)
m=a[...,3]>128
m=nd.binary_erosion(m,iterations=1)                       # без светлой каймы JPEG
al=nd.gaussian_filter(m.astype(float),0.6)
rgb=a[...,:3].copy()
edge=(al>0.02)&(al<0.98)                                    # край — к цвету контура, а не фона
rgb[edge]=rgb[edge]*0.55
cut=Image.fromarray(np.dstack([rgb,al*255]).astype(np.uint8),'RGBA')
s,tx,ty=1.05,128-104.5*1.05,221-205*1.05
W=256
def place(img):
    big=img.resize((round(img.width*s),round(img.height*s)),Image.LANCZOS).filter(ImageFilter.UnsharpMask(1.2,60,2))
    out=Image.new('RGBA',(W,W),(0,0,0,0)); out.alpha_composite(big,(round(tx),round(ty))); return out
P=lambda x,y:(x*s+tx,y*s+ty)
# нижняя челюсть: под средней линией пасти, в ширину рта с запасом, до бороды
jaw=[(52,157),(156,157),(166,178),(156,210),(52,210),(42,178)]
jm=Image.new('L',src.size,0); ImageDraw.Draw(jm).polygon(jaw,fill=255); jm=jm.filter(ImageFilter.GaussianBlur(2.2))
jawimg=cut.copy(); ja=np.asarray(jawimg).copy(); ja[...,3]=(ja[...,3].astype(float)*np.asarray(jm)/255).astype(np.uint8)
jawimg=Image.fromarray(ja,'RGBA')
# основа: под челюстью внутри пасти — тёмная глотка (видна, когда челюсть опускается)
base=cut.copy(); d=ImageDraw.Draw(base)
d.ellipse((76,146,134,190),fill=(58,14,16,255))
base=Image.alpha_composite(base, Image.new('RGBA',src.size,(0,0,0,0)))
# основа поверх глотки снова получает верхнюю часть пасти (зубы сверху) — глотка только ниже средней линии
top=cut.copy(); ta=np.asarray(top).copy(); ta[157:,:,3]=0; top=Image.fromarray(ta,'RGBA')
base=Image.alpha_composite(base,top)
place(base).save(S+"/zlico/zver-lico.png"); place(jawimg).save(S+"/zlico/zver-chelyust.png")
tochki={'glazL':P(76,111),'glazR':P(132,111),'past':[*P(72,138),*P(137,182)],'liniya':P(0,157)[1],'chelka':P(0,85)[1]}
print(json.dumps({k:(tuple(round(v,1) for v in t) if isinstance(t,(tuple,list)) else round(t,1)) for k,t in tochki.items()}))
# предпросмотр: покой, челюсть вниз 7, вверх 4
for nm,dy in [('pokoj',0),('otkryt',7),('zakryt',-4)]:
    f=Image.new('RGBA',(W,W),(40,70,130,255)); f.alpha_composite(place(base)); jj=place(jawimg); f.alpha_composite(jj,(0,dy)); f.convert('RGB').resize((512,512),Image.LANCZOS).save(S+f"/zlico/p-{nm}.png")
