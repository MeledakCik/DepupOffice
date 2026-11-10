# Set uji TERPISAH (tidak dipakai saat menyetel): latar & gangguan baru.
import numpy as np, json, random, math, sys
from PIL import Image, ImageDraw, ImageFilter
exec(open('gen.py').read().split('cases=[]')[0].replace('default_rng(7)','default_rng(99)').replace('random.seed(7)','random.seed(99)'))
def bg_marble(w,h):
    y,x=np.mgrid[0:h,0:w]; t=np.sin(x*0.01+y*0.013+np.sin(x*0.03)*2)
    return np.array([200,198,192])+(t[...,None]*np.array([18,18,18]))+noise(h,w,9)
def bg_beige(w,h): return np.array([226,214,190])+noise(h,w,5)
def bg_granite(w,h):
    g=np.array([90,88,85])+noise(h,w,22); return g
def bg_desk(w,h):
    img=np.array([150,150,155])+noise(h,w,5)
    im=Image.fromarray(np.clip(img,0,255).astype('uint8')); d=ImageDraw.Draw(im)
    d.rectangle([int(w*0.62),int(h*0.02),int(w*1.1),int(h*0.35)],fill=(35,35,40))      # laptop
    d.rounded_rectangle([int(w*0.05),int(h*0.70),int(w*0.25),int(h*0.95)],20,fill=(20,20,25))  # ponsel
    d.line([(int(w*0.8),int(h*0.6)),(int(w*0.95),int(h*0.9))],fill=(30,60,160),width=10)       # pulpen
    return np.array(im).astype(float)
def bg_darkwood(w,h): return bg_wood(w,h)*0.55
B2={'marmer':bg_marble,'krem':bg_beige,'granit':bg_granite,'meja':bg_desk,'kayugelap':bg_darkwood}
cases=[]; n=0
for bg in B2:
    for kind,frac,extra in [('struk',0.04,''),('struk',0.09,'persp'),('a4',0.30,'bayang'),('a4',0.65,'persp'),('ktp',0.08,''),('buku',0.45,''),('a4',0.90,'potong'),('struk',0.15,'blur')]:
        land = kind=='ktp'
        w,h=(H,W) if land else (W,H)
        img=B2[bg](w,h)
        D=doc(kind); dh,dw=D.shape[:2]
        area=frac*w*h; s=math.sqrt(area/(dw*dh)); ww,hh=dw*s,dh*s
        cx=w/2+rng.uniform(-0.2,0.2)*w*(1-frac); cy=h/2+rng.uniform(-0.2,0.2)*h*(1-frac)
        ang=math.radians(rng.uniform(-30,30)); ca,sa=math.cos(ang),math.sin(ang)
        base=[(-ww/2,-hh/2),(ww/2,-hh/2),(ww/2,hh/2),(-ww/2,hh/2)]
        if extra=='persp': base=[(-ww*0.38,-hh/2),(ww*0.38,-hh/2),(ww*0.55,hh/2),(-ww*0.55,hh/2)]
        quad=[]
        for (x,y) in base:
            jx,jy=rng.uniform(-0.05,0.05)*ww,rng.uniform(-0.05,0.05)*hh
            quad.append((cx+(x+jx)*ca-(y+jy)*sa, cy+(x+jx)*sa+(y+jy)*ca))
        if extra=='potong': quad=[(x,y) for x,y in quad]   # boleh keluar bingkai
        warped,mask=warp(D,quad,w,h)
        k=0.55 if extra=='bayang' else 0.25
        sh=Image.fromarray((mask*255).astype('uint8')).filter(ImageFilter.GaussianBlur(18)); sh=np.array(sh)/255.0
        img=img*(1-k*np.roll(np.roll(sh,14,0),10,1)[...,None])
        img=np.where(mask[...,None],warped,img)
        if extra=='bayang':  # bayangan tangan/ponsel melintang di atas kertas
            yy,xx=np.mgrid[0:h,0:w]; m=((xx-w*0.7)**2/(w*0.25)**2+(yy-h*0.3)**2/(h*0.2)**2)<1
            img=img*np.where(m,0.7,1.0)[...,None]
        gx=np.linspace(rng.uniform(0.75,1.0),rng.uniform(0.95,1.15),w)[None,:,None]
        gy=np.linspace(rng.uniform(0.85,1.0),rng.uniform(0.95,1.1),h)[:,None,None]
        img=img*gx*gy+noise(h,w,5)
        out=Image.fromarray(np.clip(img,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(2.2 if extra=='blur' else 0.8))
        q=[(min(max(x,0),w-1),min(max(y,0),h-1)) for x,y in quad]
        name=f"t_{n:02d}_{bg}_{kind}_{int(frac*100)}{('_'+extra) if extra else ''}.jpg"; out.save('imgs2/'+name,quality=85); n+=1
        cases.append({'file':name,'quad':[c for p in q for c in p],'w':w,'h':h})
json.dump(cases,open('cases2.json','w'),indent=0); print(len(cases))
