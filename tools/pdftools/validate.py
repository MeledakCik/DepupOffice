import pikepdf, pypdf, glob, os, sys
def text(path,pw=None):
    r=pypdf.PdfReader(path)
    if r.is_encrypted: r.decrypt(pw or "")
    return "".join(p.extract_text() for p in r.pages)
for src in sorted(glob.glob("*.pdf")):
    b=os.path.splitext(src)[0]; pw="rahasia" if "_pw" in src else ""
    orig=text(src,pw)
    res=[]
    for kind,opw in [("plain",""),("ai",""),("noai",""),("lock","uji123")]:
        f=f"out/{b}_{kind}.pdf"
        try:
            p=pikepdf.open(f,password=opw)
            probs=p.check_pdf_syntax() if hasattr(p,"check_pdf_syntax") else []
            t=text(f,opw)
            extra=""
            if kind=="lock":
                e=p.encryption; extra=f" R={e.R} bits={e.bits} copy={p.allow.extract} print={p.allow.print_highres} mod={p.allow.modify_other}"
                try: pikepdf.open(f); extra+=" TANPA-PW-TERBUKA!"
                except pikepdf.PasswordError: extra+=" tanpa-pw:ditolak"
            if kind in("ai","noai"):
                m=str(p.open_metadata()); extra=f" DMI={'DMI-PROHIBITED' in m} kw={p.docinfo.get('/Keywords')}"
            res.append(f"{kind}:{'OK' if t==orig else 'TEKS-BEDA'}{'' if not probs else ' masalah='+str(probs[:2])}{extra}")
        except Exception as e: res.append(f"{kind}:GAGAL {e}")
    print(src); [print("   ",r) for r in res]
