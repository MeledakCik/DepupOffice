import numpy as np, json, random, math
from PIL import Image, ImageDraw, ImageFilter
rng=np.random.default_rng(7); random.seed(7)
W,H=900,1200
def noise(h,w,s): return rng.normal(0,s,(h,w,1))
def bg_wood(w,h):
    y,x=np.mgrid[0:h,0:w]; t=np.sin(x*0.02+np.sin(y*0.006)*3)*0.5+np.sin(x*0.11+y*0.01)*0.15
    base=np.array([110,70,45]); img=base+(t[...,None]*np.array([28,18,12]))+noise(h,w,6); return img
def bg_dark(w,h): return np.array([48,48,52])+noise(h,w,7)
def bg_white(w,h): return np.array([214,214,210])+noise(h,w,4)
def bg_cloth(w,h):
    y,x=np.mgrid[0:h,0:w]; s=((x//40+y//40)%2)[...,None]; return np.where(s,np.array([60,90,150]),np.array([170,60,60]))+noise(h,w,8)
def bg_tiles(w,h):
    y,x=np.mgrid[0:h,0:w]; img=np.array([225,226,228])+noise(h,w,4); m=((x%180)<3)|((y%180)<3); img[m]=[150,150,155]; return img
def bg_split(w,h):  # meja kayu di atas, lantai putih di bawah (seperti foto pengguna)
    img=bg_wood(w,h); t=bg_tiles(w,h); cut=int(h*0.68)
    yy=np.arange(h)[:,None]+ (np.sin(np.arange(w)/90)*25)[None,:]
    img=np.where((yy>cut)[...,None],t,img); return img
BGS={'kayu':bg_wood,'gelap':bg_dark,'putih':bg_white,'kain':bg_cloth,'ubin':bg_tiles,'kayu+lantai':bg_split}
def doc(kind):
    if kind=='struk': w,h,col=300,620,(246,246,242)
    elif kind=='a4': w,h,col=500,707,(250,250,248)
    elif kind=='ktp': w,h,col=560,353,(170,205,235)
    else: w,h,col=500,680,(242,240,228)
    im=Image.new('RGB',(w,h),col); d=ImageDraw.Draw(im)
    if kind=='ktp':
        d.rectangle([20,20,w-20,60],fill=(40,90,160)); d.rectangle([w-170,90,w-30,300],fill=(200,120,110))
        for i in range(8): d.rectangle([30,90+i*26,30+random.randint(150,330),100+i*26],fill=(40,40,60))
    else:
        y=40
        while y<h-40:
            if kind=='buku': d.line([20,y+14,w-20,y+14],fill=(170,190,220),width=1)
            if random.random()<0.85: d.rectangle([30,y,30+random.randint(int(w*.3),int(w*.85)),y+8],fill=(50,50,60))
            y+=26 if kind!='struk' else 20
        if kind=='struk': d.rectangle([w//2-60,h//2-60,w//2+60,h//2+60],fill=(30,30,30))
    return np.array(im).astype(float)
def warp(src, quad, out_w, out_h):
    # inverse map via homography
    h,w=src.shape[:2]
    srcpts=np.array([[0,0],[w,0],[w,h],[0,h]],float); dst=np.array(quad,float)
    A=[];B=[]
    for (x,y),(u,v) in zip(dst,srcpts):
        A.append([x,y,1,0,0,0,-u*x,-u*y]); B.append(u); A.append([0,0,0,x,y,1,-v*x,-v*y]); B.append(v)
    p=np.linalg.solve(np.array(A),np.array(B)); Hm=np.append(p,1).reshape(3,3)
    yy,xx=np.mgrid[0:out_h,0:out_w]; P=np.stack([xx.ravel(),yy.ravel(),np.ones(xx.size)])
    q=Hm@P; u=q[0]/q[2]; v=q[1]/q[2]
    m=(u>=0)&(u<w-1)&(v>=0)&(v<h-1)
    out=np.zeros((out_h*out_w,3)); ui=u[m].astype(int); vi=v[m].astype(int)
    out[m]=src[vi,ui]; mask=np.zeros(out_h*out_w,bool); mask[m]=True
    return out.reshape(out_h,out_w,3), mask.reshape(out_h,out_w)
cases=[]; n=0
specs=[]
for bg in BGS:
    for kind,frac in [('struk',0.05),('struk',0.12),('a4',0.25),('a4',0.55),('ktp',0.10),('buku',0.35),('a4',0.80)]:
        specs.append((bg,kind,frac))
for (bg,kind,frac) in specs:
    land = kind=='ktp' and random.random()<0.5
    w,h=(H,W) if land else (W,H)
    img=BGS[bg](w,h)
    if random.random()<0.5:  # kabel gelap
        im=Image.fromarray(np.clip(img,0,255).astype('uint8')); d=ImageDraw.Draw(im)
        pts=[(random.randint(0,w//3),0)]+[(random.randint(0,w//2),k*h//5) for k in range(1,6)]
        d.line(pts,fill=(20,20,20),width=6); img=np.array(im).astype(float)
    D=doc(kind); dh,dw=D.shape[:2]
    area=frac*w*h; s=math.sqrt(area/(dw*dh)); ww,hh=dw*s,dh*s
    cx=w/2+rng.uniform(-0.15,0.15)*w*(1-frac); cy=h/2+rng.uniform(-0.15,0.15)*h*(1-frac)
    ang=math.radians(rng.uniform(-22,22)); ca,sa=math.cos(ang),math.sin(ang)
    base=[(-ww/2,-hh/2),(ww/2,-hh/2),(ww/2,hh/2),(-ww/2,hh/2)]
    quad=[]
    for (x,y) in base:
        jx,jy=rng.uniform(-0.07,0.07)*ww,rng.uniform(-0.07,0.07)*hh
        X=cx+(x+jx)*ca-(y+jy)*sa; Y=cy+(x+jx)*sa+(y+jy)*ca; quad.append((X,Y))
    quad=[(min(max(x,2),w-3),min(max(y,2),h-3)) for x,y in quad]
    warped,mask=warp(D,quad,w,h)
    # bayangan lembut di bawah kertas
    sh=Image.fromarray((mask*255).astype('uint8')).filter(ImageFilter.GaussianBlur(14)); sh=np.array(sh)/255.0
    img=img*(1-0.25*np.roll(np.roll(sh,8,0),6,1)[...,None])
    img=np.where(mask[...,None],warped,img)
    # gradien cahaya & noise
    gx=np.linspace(rng.uniform(0.8,1.0),rng.uniform(0.95,1.1),w)[None,:,None]
    img=img*gx+noise(h,w,3)
    out=Image.fromarray(np.clip(img,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(0.8))
    name=f"syn_{n:02d}_{bg}_{kind}_{int(frac*100)}.jpg"; out.save('imgs/'+name,quality=88); n+=1
    cases.append({'file':name,'quad':[c for p in quad for c in p],'w':w,'h':h})
# foto pengguna (dari screenshot): ground truth struk ditandai manual
from PIL import Image as I
sc=I.open('/mnt/user-data/attachments/f5159fbd-cf39-5a33-badc-5e86897bf82d/e16c610f-image.png').convert('RGB')
crop=sc.crop((248,588,968,1556)); crop.save('imgs/user_struk.jpg',quality=92)
ox,oy=248,588; gt=[(473,915),(733,895),(780,1252),(487,1273)]
cases.append({'file':'user_struk.jpg','quad':[c for (x,y) in gt for c in (x-ox,y-oy)],'w':crop.size[0],'h':crop.size[1]})
json.dump(cases,open('cases.json','w'),indent=0)
print(len(cases))
