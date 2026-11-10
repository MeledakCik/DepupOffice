# Set 4: struk di atas laptop (permukaan perak kekuningan hampir seputih kertas, kisi speaker, keyboard gelap)
import numpy as np, json, random, math
from PIL import Image, ImageDraw, ImageFilter
exec(open("gen.py").read().split("cases=[]")[0].replace("out=np.zeros((out_h*out_w,3))","out=np.zeros((out_h*out_w,src.shape[2]))").replace("reshape(out_h,out_w,3)","reshape(out_h,out_w,src.shape[2])").replace('default_rng(7)','default_rng(4242)').replace('random.seed(7)','random.seed(4242)'))
def laptop(w,h,tone):
    img=np.zeros((h,w,3))+np.array(tone)+noise(h,w,3)
    im=Image.fromarray(np.clip(img,0,255).astype('uint8')); d=ImageDraw.Draw(im)
    kx0,ky0,kx1,ky1=int(w*0.40),int(h*0.15),int(w*1.02),int(h*0.55)
    d.rectangle([kx0,ky0,kx1,ky1],fill=(25,25,28))
    for r in range(6):
        for c in range(9):
            x=kx0+12+c*int(w*0.075); y=ky0+12+r*int(h*0.062)
            d.rounded_rectangle([x,y,x+int(w*0.062),y+int(h*0.05)],8,fill=(45,45,50))
            d.text((x+10,y+10),'AB'[(r+c)%2],fill=(220,220,220))
    for y in range(int(h*0.15),int(h*0.55),9):            # kisi speaker
        for x in range(int(w*0.05),int(w*0.33),9): d.ellipse([x,y,x+3,y+3],fill=(90,85,75))
    d.rectangle([int(w*0.38),int(h*0.65),int(w*0.98),int(h*0.95)],outline=tuple(int(t*0.85) for t in tone),width=4)  # trackpad
    d.rectangle([0,0,w,int(h*0.06)],fill=(15,15,18))       # layar/engsel
    return np.array(im).astype(float)
cases=[]; n=0
tones=[(240,228,192),(232,232,236),(236,226,205),(225,225,228)]
for t_i,tone in enumerate(tones):
    for kind,frac,cx,cy in [('struk',0.10,0.5,0.45),('struk',0.14,0.42,0.5),('a4',0.30,0.55,0.55)]:
        w,h=W,H; img=laptop(w,h,tone)
        D=doc(kind); dh,dw=D.shape[:2]
        area=frac*w*h; s=math.sqrt(area/(dw*dh)); ww,hh=dw*s,dh*s
        cx=cx*w; cy=cy*h; ang=math.radians(rng.uniform(-8,8)); ca,sa=math.cos(ang),math.sin(ang)
        quad=[]
        for (x,y) in [(-ww/2,-hh/2),(ww/2,-hh/2),(ww/2,hh/2),(-ww/2,hh/2)]:
            jx,jy=rng.uniform(-0.03,0.03)*ww,rng.uniform(-0.03,0.03)*hh
            quad.append((cx+(x+jx)*ca-(y+jy)*sa, cy+(x+jx)*sa+(y+jy)*ca))
        warped,mask=warp(D,quad,w,h)
        sh=Image.fromarray((mask*255).astype('uint8')).filter(ImageFilter.GaussianBlur(8)); sh=np.array(sh)/255.0
        img=img*(1-0.12*np.roll(np.roll(sh,5,0),3,1)[...,None])
        img=np.where(mask[...,None],warped,img)
        warm=np.array([1.0,0.98,0.93]) if t_i%2==0 else np.array([1,1,1])
        img=img*warm*np.linspace(rng.uniform(0.85,1),1.05,w)[None,:,None]+noise(h,w,3)
        out=Image.fromarray(np.clip(img,0,255).astype('uint8')).filter(ImageFilter.GaussianBlur(1.0))
        name=f"n_l{n:02d}_laptop{t_i}_{kind}_{int(frac*100)}.jpg"; out.save('imgs3b/'+name,quality=86)
        Image.fromarray((mask*255).astype('uint8')).resize((200,200),Image.NEAREST).save('imgs3b/'+name.replace('.jpg','_mask.png'))
        n+=1; cases.append({'file':name,'quad':[c for p in quad for c in p],'w':w,'h':h})
c3=[c for c in __import__('json').load(open('cases3.json')) if not c['file'].startswith('n_l')]
c3+=cases
open('cases3.json','w').write('[\n'+',\n'.join(json.dumps(x) for x in c3)+'\n]'); print(len(c3))
