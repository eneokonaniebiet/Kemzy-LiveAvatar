#include "onnxruntime_c_api.h"
#include <cmath>
#include <cstdint>
#include <new>

namespace {
struct Kernel { const OrtApi* api; };
void* ORT_API_CALL createKernel(const OrtCustomOp*, const OrtApi* api, const OrtKernelInfo*) { return new (std::nothrow) Kernel{api}; }
void ORT_API_CALL destroyKernel(void* p) { delete static_cast<Kernel*>(p); }
const char* ORT_API_CALL name(const OrtCustomOp*) { return "GridSample3D"; }
const char* ORT_API_CALL provider(const OrtCustomOp*) { return "CPUExecutionProvider"; }
size_t ORT_API_CALL inputCount(const OrtCustomOp*) { return 2; }
size_t ORT_API_CALL outputCount(const OrtCustomOp*) { return 1; }
ONNXTensorElementDataType ORT_API_CALL inputType(const OrtCustomOp*, size_t) { return ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT; }
ONNXTensorElementDataType ORT_API_CALL outputType(const OrtCustomOp*, size_t) { return ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT; }
OrtCustomOpInputOutputCharacteristic ORT_API_CALL characteristic(const OrtCustomOp*, size_t) { return INPUT_OUTPUT_REQUIRED; }
OrtMemType ORT_API_CALL memoryType(const OrtCustomOp*, size_t) { return OrtMemTypeDefault; }
int ORT_API_CALL startVersion(const OrtCustomOp*) { return 1; }
int ORT_API_CALL endVersion(const OrtCustomOp*) { return 22; }

inline float sample(const float* x, int64_t n, int64_t c, int64_t d, int64_t h, int64_t w,
                    float gx, float gy, float gz, bool alignCorners) {
    const auto coord = [](float g, int64_t size, bool align) {
        return align ? ((g + 1.f) * (size - 1)) * 0.5f : ((g + 1.f) * size - 1.f) * 0.5f;
    };
    const float fx=coord(gx,w,alignCorners), fy=coord(gy,h,alignCorners), fz=coord(gz,d,alignCorners);
    if (fx < -1.f || fx > static_cast<float>(w) || fy < -1.f || fy > static_cast<float>(h) || fz < -1.f || fz > static_cast<float>(d)) return 0.f;
    const int64_t x0=static_cast<int64_t>(std::floor(fx)), y0=static_cast<int64_t>(std::floor(fy)), z0=static_cast<int64_t>(std::floor(fz));
    const int64_t x1=x0+1, y1=y0+1, z1=z0+1; const float wx=fx-x0, wy=fy-y0, wz=fz-z0;
    auto at=[&](int64_t zz,int64_t yy,int64_t xx)->float{ if(zz<0||zz>=d||yy<0||yy>=h||xx<0||xx>=w)return 0.f; const int64_t idx=(((n*c+c)*d+zz)*h+yy)*w+xx; return x[idx]; };
    const float c000=at(z0,y0,x0),c001=at(z0,y0,x1),c010=at(z0,y1,x0),c011=at(z0,y1,x1);
    const float c100=at(z1,y0,x0),c101=at(z1,y0,x1),c110=at(z1,y1,x0),c111=at(z1,y1,x1);
    const float c00=c000+(c001-c000)*wx,c01=c010+(c011-c010)*wx,c10=c100+(c101-c100)*wx,c11=c110+(c111-c110)*wx;
    const float a=c00+(c01-c00)*wy,b=c10+(c11-c10)*wy; return a+(b-a)*wz;
}

OrtStatusPtr ORT_API_CALL compute(void* kernelPtr, OrtKernelContext* ctx) {
    const OrtApi* api=static_cast<Kernel*>(kernelPtr)->api; const OrtValue* xv=nullptr; const OrtValue* gv=nullptr;
    if(auto s=api->KernelContext_GetInput(ctx,0,&xv))return s; if(auto s=api->KernelContext_GetInput(ctx,1,&gv))return s;
    OrtTensorTypeAndShapeInfo *xs=nullptr,*gs=nullptr; if(auto s=api->GetTensorTypeAndShape(xv,&xs))return s;
    if(auto s=api->GetTensorTypeAndShape(gv,&gs)){api->ReleaseTensorTypeAndShapeInfo(xs);return s;}
    size_t xr=0,gr=0;api->GetDimensionsCount(xs,&xr);api->GetDimensionsCount(gs,&gr);
    if(xr!=5||gr!=5){api->ReleaseTensorTypeAndShapeInfo(xs);api->ReleaseTensorTypeAndShapeInfo(gs);return api->CreateStatus(ORT_INVALID_ARGUMENT,"GridSample3D requires 5-D input and grid");}
    int64_t xd[5],gd[5];api->GetDimensions(xs,xd,5);api->GetDimensions(gs,gd,5);
    if(gd[4]!=3||xd[0]!=gd[0]){api->ReleaseTensorTypeAndShapeInfo(xs);api->ReleaseTensorTypeAndShapeInfo(gs);return api->CreateStatus(ORT_INVALID_ARGUMENT,"GridSample3D expects grid shape [N,D,H,W,3]");}
    const int64_t outShape[5]={xd[0],xd[1],gd[1],gd[2],gd[3]};OrtValue* out=nullptr;
    if(auto s=api->KernelContext_GetOutput(ctx,0,outShape,5,&out)){api->ReleaseTensorTypeAndShapeInfo(xs);api->ReleaseTensorTypeAndShapeInfo(gs);return s;}
    void *xp=nullptr,*gp=nullptr,*op=nullptr;api->GetTensorMutableData(const_cast<OrtValue*>(xv),&xp);api->GetTensorMutableData(const_cast<OrtValue*>(gv),&gp);api->GetTensorMutableData(out,&op);
    const float*x=static_cast<const float*>(xp),*grid=static_cast<const float*>(gp);float*y=static_cast<float*>(op);const int64_t N=xd[0],C=xd[1],D=xd[2],H=xd[3],W=xd[4],OD=gd[1],OH=gd[2],OW=gd[3];
    for(int64_t n=0;n<N;++n)for(int64_t c=0;c<C;++c)for(int64_t z=0;z<OD;++z)for(int64_t yy=0;yy<OH;++yy)for(int64_t xx=0;xx<OW;++xx){const int64_t gi=(((n*OD+z)*OH+yy)*OW+xx)*3;const float v=sample(x,n,c,D,H,W,grid[gi],grid[gi+1],grid[gi+2],false);const int64_t oi=((((n*C+c)*OD+z)*OH+yy)*OW+xx);y[oi]=v;}
    api->ReleaseTensorTypeAndShapeInfo(xs);api->ReleaseTensorTypeAndShapeInfo(gs);return nullptr;
}
OrtStatusPtr ORT_API_CALL registerOps(OrtSessionOptions* options,const OrtApiBase* base){const OrtApi* api=base->GetApi(ORT_API_VERSION);if(!api)return nullptr;static OrtCustomOp op{};static bool initialized=false;if(!initialized){op.version=ORT_API_VERSION;op.CreateKernel=createKernel;op.GetName=name;op.GetExecutionProviderType=provider;op.GetInputType=inputType;op.GetInputTypeCount=inputCount;op.GetOutputType=outputType;op.GetOutputTypeCount=outputCount;op.KernelDestroy=destroyKernel;op.KernelComputeV2=compute;op.GetInputCharacteristic=characteristic;op.GetOutputCharacteristic=characteristic;op.GetInputMemoryType=memoryType;op.GetStartVersion=startVersion;op.GetEndVersion=endVersion;initialized=true;}static OrtCustomOpDomain* domain=nullptr;if(!domain){if(auto s=api->CreateCustomOpDomain("",&domain))return s;if(auto s=api->CustomOpDomain_Add(domain,&op))return s;}return api->AddCustomOpDomain(options,domain);}
}
extern "C" ORT_EXPORT OrtStatus* ORT_API_CALL RegisterCustomOps(OrtSessionOptions* options,const OrtApiBase* api){return registerOps(options,api);}
