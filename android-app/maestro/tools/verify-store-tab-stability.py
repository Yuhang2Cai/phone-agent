"""从实际录屏逐帧检测门店摘要顶部，页签切换不应改变整个列表的起始位置。"""
import argparse,json
from pathlib import Path
import cv2,numpy as np
p=argparse.ArgumentParser();p.add_argument('video');p.add_argument('--output',required=True);a=p.parse_args()
v=cv2.VideoCapture(a.video); ys=[]
while True:
 ok,f=v.read()
 if not ok: break
 h,w=f.shape[:2]; b,g,r=cv2.split(f); mask=(g>r*1.35)&(g>b*.85)&(r<65)&(g<145)&(g>25)
 rows=np.mean(mask,axis=1)>.45; rows[:int(h*.08)]=False; rows[int(h*.5):]=False
 indices=np.flatnonzero(rows); runs=np.split(indices,np.where(np.diff(indices)>1)[0]+1)
 candidates=[run for run in runs if len(run)>h*.04]
 if candidates: ys.append(int(candidates[0][0]))
if len(ys)<10: raise RuntimeError('录屏有效门店摘要不足10帧，不能判定通过')
result={'frames':len(ys),'headerTopMin':min(ys),'headerTopMax':max(ys),'verticalShiftPixels':max(ys)-min(ys),'passed':max(ys)-min(ys)<=8}
Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result));raise SystemExit(0 if result['passed'] else 1)
