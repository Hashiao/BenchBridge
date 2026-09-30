// 固定计算区域与迭代上限，输出每个像素的逃逸次数。
// Fix the viewport and iteration limit, and output each pixel's escape count.
void main() {
    uint id=gl_GlobalInvocationID.x;if(id>=p.count)return;
    precise REAL x=REAL(id%p.width)/REAL(p.width-1),y=REAL(id/p.width)/REAL(p.height-1);
#ifdef JULIA
    precise REAL zx=x*REAL(3)-REAL(1.5),zy=y*REAL(2)-REAL(1),cx=REAL(-0.7),cy=REAL(0.27015);
#else
    precise REAL zx=REAL(0),zy=REAL(0),cx=x*REAL(3)-REAL(2),cy=y*REAL(3)-REAL(1.5);
#endif
    uint n=0;
    while(n<128u){
        precise REAL xx=zx*zx,yy=zy*zy;if(xx+yy>REAL(4))break;
        zy=REAL(2)*zx*zy+cy;zx=xx-yy+cx;++n;
    }
    outputData.values[id]=n;
}
