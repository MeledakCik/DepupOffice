# Set uji 3: warna "nyaru" (kertas hampir sewarna latar) + tepi kertas melengkung. Ground truth = MASKER kertas.
import numpy as np, json, random, math
from PIL import Image, ImageDraw, ImageFilter
exec(open("gen.py").read().split("cases=[]")[0].replace("out=np.zeros((out_h*out_w,3))","out=np.zeros((out_h*out_w,src.shape[2]))").replace("reshape(out_h,out_w,3)","reshape(out_h,out_w,src.shape[2])").replace('default_rng(7)','default_rng(2026)').replace('random.seed(7)','random.seed(2026)'))
def flat(c,s=4): return lambda w,h: np.array(c)+noise(h,w,s)
def sheet(w,h):  # kain sprei putih berkerut halus
    y,x=np.mgrid[0:h,0:w]; t=np.sin(x*0.013+np.sin(y*0.01)*2)*0.5+np.sin(y*0.021)*0.5
    return np.array([232,232,236])+t[...,None]*10+noise(h,w,3)
def lightwood(w,h): return bg_wood(w,h)*1.0+np.array([90,80,70])
B3={'putih':flat([238,238,236]),'abu':flat([212,212,214]),'krem':flat([238,232,218]),'sprei':sheet,
    'kayumuda':lightwood,'kayu+lantai':bg_split}
cases=[]; n=0
for bg in B3:
    for kind,frac,curl in [('struk',0.06,0.0),('struk',0.12,0.03),('a4',0.35,0.0),('a4',0.55,0.025),('struk',0.18,0.04)]:
        w,h=W,H
        img=B3[bg](w,h)
        D=doc(kind); dh,dw=D.shape[:2]
        # tepi melengkung: alpha mask dengan sisi atas/kanan bergelombang
        a=Image.new('L',(dw,dh),255); d=ImageDraw.Draw(a)
        if curl>0:
            amp=curl*dh
            pts=[(x, amp*(0.5+0.5*math.sin(x/dw*math.pi*random.uniform(1,2)+random.uniform(0,3)))) for x in range(0,dw+1,4)]
            d.polygon([(0,0)]+pts+[(dw,0)],fill=0)
            ampx=curl*dw
            pts=[(dw-ampx*(0.5+0.5*math.sin(y/dh*math.pi*random.uniform(1,2))),y) for y in range(0,dh+1,4)]
            d.polygon([(dw,0)]+pts+[(dw,dh)],fill=0)
        A=np.array(a).astype(float)[...,None]/255
        Dm=np.concatenate([D,A*255],axis=2)
        area=frac*w*h; s=math.sqrt(area/(dw*dh)); ww,hh=dw*s,dh*s
        cx=w/2+rng.uniform(-0.2,0.2)*w*(1-frac); cy=h/2+rng.uniform(-0.2,0.2)*h*(1-frac)
        ang=math.radians(rng.uniform(-25,25)); ca,sa=math.cos(ang),math.sin(ang)
        quad=[]
        for (x,y) in [(-ww/2,-hh/2),(ww/2,-hh/2),(ww/2,hh/2),(-ww/2,hh/2)]:
            jx,jy=rng.uniform(-0.05,0.05)*ww,rng.uniform(-0.05,0.05)*hh
            quad.append((cx+(x+jx)*ca-(y+jy)*sa, cy+(x+jx)*sa+(y+jy)*ca))
        warped,mask=warp(Dm,quad,w,h)
        alpha=(warped[...,3:4]/255)*mask[...,None]
        sh=Image.fromarray((alpha[...,0]*255).astype('uint8')).filter(ImageFilter.GaussianBlur(10)); sh=np.array(sh)/255.0
        img=img*(1-0.18*np.roll(np.roll(sh,6,0),4,1)[...,None])
        img=img*(1-alpha)+warped[...,:3]*alpha
        gx=np.linspace(rng.uniform(0.85,1.0),rng.uniform(0.95,1.05),w)[None,:,None]
        img=img*gx+noise(h,w,3)
        out=Image.fromarray(np.clip(img,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(1.0))
        name=f"n_{n:02d}_{bg}_{kind}_{int(frac*100)}{'_lengkung' if curl else ''}.jpg"; out.save('imgs3b/'+name,quality=86)
        Image.fromarray(((alpha[...,0]>0.5)*255).astype('uint8')).resize((200,200),Image.NEAREST).save('imgs3b/'+name.replace('.jpg','_mask.png'))
        n+=1
        cases.append({'file':name,'quad':[c for p in quad for c in p],'w':w,'h':h})
open('cases3.json','w').write('[\n'+',\n'.join(json.dumps(x) for x in cases)+'\n]'); print(len(cases))
