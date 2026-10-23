import sys
from PIL import Image
import numpy as np
from scipy import ndimage as nd
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
S=sys.argv[1]
im=Image.open(MAKET + "/ref-zver-list.jpg").convert("RGB")
c=im.crop((15,572,240,788)); a=np.asarray(c).astype(float)
mx=a.max(2); mn=a.min(2); sat=(mx-mn)/np.maximum(mx,1); val=mx
bg=np.median(a[:6,:6].reshape(-1,3),0); d=np.abs(a-bg).sum(2)
obj=(sat>0.18)|(val<120)|(d>70)
lab,n=nd.label(~obj)
edge=set(np.unique(np.r_[lab[0],lab[-1],lab[:,0],lab[:,-1]]))-{0}
m=~np.isin(lab,list(edge))
m=nd.binary_opening(m,iterations=1)
lab2,n2=nd.label(m); sizes=nd.sum(m,lab2,range(1,n2+1)); m=lab2==(1+int(np.argmax(sizes)))
m=nd.binary_fill_holes(m)
ys,xs=np.where(m); print('size',c.size,'bbox',xs.min(),xs.max(),ys.min(),ys.max())
alpha=nd.gaussian_filter(m.astype(float),0.7)
rgba=np.dstack([a,alpha*255]).astype(np.uint8)
Image.fromarray(rgba,'RGBA').save(S+"/zlico/vyrez.png")
prev=Image.new('RGB',c.size,(30,60,120)); r=Image.fromarray(rgba,'RGBA'); prev.paste(r,(0,0),r)
prev.resize((c.size[0]*3,c.size[1]*3),Image.LANCZOS).save(S+"/zlico/prev.png")
