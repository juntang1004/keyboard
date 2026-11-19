import struct, re
def records(path):
    with open(path,'rb') as f:
        while True:
            h=f.read(12)
            if len(h)<12: return
            n=struct.unpack('<Q',h[:8])[0]
            d=f.read(n); f.read(4)
            yield d
def texts(path):
    for d in records(path):
        t=d.decode('utf-8','ignore')
        i=t.find('_START_ARTICLE_')
        if i<0: continue
        t=t[i:]
        for para in re.split(r'_START_ARTICLE_|_START_SECTION_|_START_PARAGRAPH_|_NEWLINE_', t):
            para=para.strip()
            if len(para)>20: yield para
