"""Conditional single-triangle sampling variance; requires NumPy, does not benchmark the GPU.

Run from the repository root. Results go to build/light-sampling-analysis/variance.json.
"""
from pathlib import Path
import numpy as np
import json

# Deterministic quadrature on one physical triangle, independent of production shaders.
# Conditional on choosing this emitter. It excludes light-tree variance and GPU cost.
def integrate(resolution):
    u=(np.arange(resolution,dtype=np.float64)+.5)/resolution
    a=np.sqrt(u)[:,None]
    v=u[None,:]
    x=np.broadcast_to(-.5+a*(1-v),(resolution,resolution)).ravel()
    y=np.broadcast_to(-.5+a*v,(resolution,resolution)).ravel()
    area=.5
    rows=[]
    for name,distance,pattern,occluded in [
        ('near_uniform',.1,'uniform',False),
        ('near_half_occluded',.1,'uniform',True),
        ('far_uniform',8.,'uniform',False),
        ('far_sparse',8.,'sparse',False)]:
        dx=x+1/6;dy=y+1/6
        r2=dx*dx+dy*dy+distance*distance
        geometry=distance*distance/(r2*r2)
        emission=np.ones_like(x) if pattern=='uniform' else np.where(x>.25,1.,.001)
        visibility=np.ones_like(x) if not occluded else (x>-1/6).astype(np.float64)
        value=emission*geometry*visibility
        integral=area*np.mean(value)
        projected=geometry/(area*np.mean(geometry))
        texture=emission/(area*np.mean(emission))
        bsdf=geometry/np.pi # area density of a full cosine-hemisphere sample, including misses
        proposals={'uniform':np.full_like(x,1/area),'texture':texture,'mixture':.5*texture+.5*projected,'projected':projected}
        row={'case':name,'integral':integral,'projected_area':float(area*np.mean(geometry)),'statistics':{}}
        for label,pdf in proposals.items():
            second=area*np.mean(value*value/pdf)
            variance=max(0.,second-integral*integral)
            wl=pdf*pdf/(pdf*pdf+bsdf*bsdf);wb=1-wl
            mean_l=area*np.mean(value*wl);mean_b=area*np.mean(value*wb)
            variance_mis=max(0.,area*np.mean(value*value*wl*wl/pdf)-mean_l*mean_l)
            variance_mis+=max(0.,area*np.mean(value*value*wb*wb/bsdf)-mean_b*mean_b)
            row['statistics'][label]={'relative_variance':float(variance/integral**2),'relative_variance_with_bsdf_mis':float(variance_mis/integral**2)}
        rows.append(row)
    return rows

results={str(n):integrate(n) for n in [512,1024]}
output=Path('build/light-sampling-analysis/variance.json')
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(results,indent=2),encoding='utf-8')
for row in results['1024']:
    print(row['case'], 'integral=',round(row['integral'],8))
    for method,value in row['statistics'].items():print(' ',method, value)
