import sys
from PIL import Image
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
out=sys.argv[1]; names=sys.argv[2:]
ims=[Image.open(MAKET + "/ref-zver-2.jpg").convert('RGB')]+[Image.open('/home/user/3dplus/renders/'+n).convert('RGB') for n in names]
H=420; ims=[i.resize((round(i.width*H/i.height),H)) for i in ims]
o=Image.new('RGB',(sum(i.width for i in ims),H),'white');x=0
for i in ims: o.paste(i,(x,0)); x+=i.width
o.save(out)
