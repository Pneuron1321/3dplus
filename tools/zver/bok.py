import sys
from PIL import Image
import numpy as np
from scipy import ndimage as nd
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
S=sys.argv[1]
im=Image.open(MAKET + "/ref-zver-list.jpg").convert("RGB")
c=im.crop((236,566,470,790)); a=np.asarray(c).astype(float)
mx=a.max(2); mn=a.min(2); sat=(mx-mn)/np.maximum(mx,1)
bg=np.median(a[:5,:5].reshape(-1,3),0); d=np.abs(a-bg).sum(2)
obj=(sat>0.18)|(mx<110)|(d>70)
# размерная линия справа и подпись — прочь: правее x=200 ничего зверского нет
obj[:,205:]=False
lab,n=nd.label(~obj)
edge=set(np.unique(np.r_[lab[0],lab[-1],lab[:,0],lab[:,-1]]))-{0}
m=~np.isin(lab,list(edge)); m=nd.binary_opening(m,iterations=1)
lab2,n2=nd.label(m); sz=nd.sum(m,lab2,range(1,n2+1)); m=lab2==(1+int(np.argmax(sz))); m=nd.binary_fill_holes(m)
ys,xs=np.where(m); print('size',c.size,'bbox',xs.min(),xs.max(),ys.min(),ys.max())
al=nd.gaussian_filter(m.astype(float),0.6)
Image.fromarray(np.dstack([a,al*255]).astype(np.uint8),'RGBA').save(S+"/zlico/bok.png")
p=Image.new('RGB',c.size,(30,60,120)); r=Image.open(S+"/zlico/bok.png"); p.paste(r,(0,0),r)
from PIL import ImageDraw
dd=ImageDraw.Draw(p)
for y in range(0,c.size[1],20): dd.line([(0,y),(6,y)],fill=(255,255,0)); dd.text((8,y-5),str(y),fill=(255,255,0))
for x in range(0,c.size[0],20): dd.line([(x,0),(x,6)],fill=(255,255,0)); dd.text((x-5,8),str(x),fill=(255,255,0))
p.resize((c.size[0]*3,c.size[1]*3),Image.LANCZOS).save(S+"/zlico/bok-prev.png")
f=Image.open(S+"/zlico/vyrez.png"); p2=Image.new('RGB',f.size,(30,60,120)); p2.paste(f,(0,0),f); d2=ImageDraw.Draw(p2)
for y in range(0,f.size[1],20): d2.line([(0,y),(6,y)],fill=(255,255,0)); d2.text((8,y-5),str(y),fill=(255,255,0))
for x in range(0,f.size[0],20): d2.line([(x,0),(x,6)],fill=(255,255,0)); d2.text((x-5,8),str(x),fill=(255,255,0))
p2.resize((f.size[0]*3,f.size[1]*3),Image.LANCZOS).save(S+"/zlico/front-prev.png")
