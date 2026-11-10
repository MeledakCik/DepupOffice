# Set 5: buku tulis terbuka 2 halaman. GT = halaman yang dituju.
import numpy as np, json, random, math
from PIL import Image, ImageDraw, ImageFilter
exec(open("gen.py").read().split("cases=[]")[0].replace('default_rng(7)','default_rng(555)').replace('random.seed(7)','random.seed(555)'))
def page(written, pw=500, ph=680):
    im=Image.new('RGB',(pw,ph),(245,243,232)); d=ImageDraw.Draw(im)
    for y in range(60,ph-30,22): d.line([15,y,pw-15,y],fill=(170,185,215),width=1)
    if written:
        y=70
        while y<ph-60:
            x=40+random.randint(0,60)
            while x<pw-60 and random.random()<0.93:
                wlen=random.randint(15,60)
                for k in range(0,wlen,5):
                    d.line([x+k,y+random.randint(-6,2),x+k+3,y+random.randint(-2,6)],fill=(40,40,90),width=2)
                x+=wlen+random.randint(8,20)
            y+=22*random.choice([1,1,2])
    return np.array(im).astype(float)
cases=[]; n=0
specs=[('kosong','isi',False),('isi','kosong',False),('kosong','isi',True),('isi','isi',True),('isi','isi',False),('isi','kosong',True)]
for bgk in ['kayu','gelap','kayu+lantai']:
    for (L,R,cut) in specs:
        w,h=W,H; img=BGS[bgk](w,h)
        P1=page(L=='isi'); P2=page(R=='isi')
        sp=np.concatenate([P1,np.zeros((680,6,3))+90,P2],1)   # punggung
        # bayangan lipatan
        xs=np.arange(sp.shape[1]); c=500+3; sh=1-0.35*np.exp(-((xs-c)/18.0)**2); sp=sp*sh[None,:,None]
        dh,dw=sp.shape[:2]
        sc=(0.95 if cut else 0.8)*w/dw; ww,hh=dw*sc,dh*sc
        cx=w*(0.38 if cut else 0.5); cy=h*0.5
        ang=math.radians(rng.uniform(-6,6)); ca,sa=math.cos(ang),math.sin(ang)
        quad=[]
        for (x,y) in [(-ww/2,-hh/2),(ww/2,-hh/2),(ww/2,hh/2),(-ww/2,hh/2)]:
            jx,jy=rng.uniform(-0.02,0.02)*ww,rng.uniform(-0.02,0.02)*hh
            quad.append((cx+(x+jx)*ca-(y+jy)*sa, cy+(x+jx)*sa+(y+jy)*ca))
        warped,mask=warp(sp,quad,w,h)
        img=np.where(mask[...,None],warped,img)+noise(h,w,3)
        out=Image.fromarray(np.clip(img,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(1.0))
        # GT: halaman yang dituju
        def lerp(a,b,t): return (a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t)
        f=503/dw
        TL,TR,BR,BL=quad
        if (L=='kosong' and R=='isi') or (L=='isi' and R=='isi' and cut):
            g=[lerp(TL,TR,f+0.01),TR,BR,lerp(BL,BR,f+0.01)]
        elif L=='isi' and R=='kosong':
            g=[TL,lerp(TL,TR,f-0.01),lerp(BL,BR,f-0.01),BL]
        else: g=quad
        g=[(min(max(x,0),w-1),min(max(y,0),h-1)) for x,y in g]
        name=f"b_{n:02d}_{bgk}_{L}-{R}{'_terpotong' if cut else ''}.jpg"; out.save('imgs/'+name,quality=88); n+=1
        cases.append({'file':name,'quad':[v for p in g for v in p],'w':w,'h':h})
c=[x for x in json.load(open('cases.json')) if not x['file'].startswith('b_')]+cases
open('cases.json','w').write('[\n'+',\n'.join(json.dumps(x) for x in c)+'\n]'); print(len(c))
