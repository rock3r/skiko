#ifdef SK_DIRECT3D

#include <locale>
#include <Windows.h>
#include <jawt_md.h>
#include <d3d12sdklayers.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <dxgi1_6.h>
#include "jni_helpers.h"
#include "exceptions_handler.h"
#include "window_util.h"

#include "SkColorSpace.h"
#include "ganesh/d3d/GrD3DBackendSurface.h"
#include "ganesh/GrDirectContext.h"
#include "ganesh/GrBackendSurface.h"
#include "SkSurface.h"

#include "ganesh/d3d/GrD3DTypes.h"
#include "ganesh/d3d/GrD3DBackendContext.h"
#include "ganesh/d3d/GrD3DDirectContext.h"

class DirectXOffscreenDevice
{
public:
    GrD3DBackendContext backendContext;

    ID3D12CommandAllocator* commandAllocator;
    ID3D12GraphicsCommandList* commandList;

    ID3D12Fence* fence;
    HANDLE fenceEvent;
    UINT64 fenceValue = 0;

    ~DirectXOffscreenDevice()
    {
        if (fenceEvent) {
            CloseHandle(fenceEvent);
        }

        if (fence) {
            fence->Release();
        }

        if (commandList) {
            commandList->Release();
        }

        if (commandAllocator) {
            commandAllocator->Release();
        }

        backendContext.fQueue.reset(nullptr);
        backendContext.fDevice.reset(nullptr);
        backendContext.fAdapter.reset(nullptr);
    }
};

UINT calculateRowPitch(UINT width) {
    UINT rowPitch = width * 4; // 4 bytes per pixel for DXGI_FORMAT_B8G8R8A8_UNORM
    rowPitch = (rowPitch + (D3D12_TEXTURE_DATA_PITCH_ALIGNMENT - 1)) & ~(D3D12_TEXTURE_DATA_PITCH_ALIGNMENT - 1);
    return rowPitch;
}

class DirectXOffScreenTexture {
public:
    int width;
    int height;
    ID3D12Resource* resource;
    ID3D12Resource* readbackBufferResource;
    
    DirectXOffScreenTexture(DirectXOffscreenDevice* device, int _width, int _height) {
        width = _width;
        height = _height;
        D3D12_RESOURCE_DESC textureDesc;
        textureDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        textureDesc.Alignment = 0;
        textureDesc.Width = _width;
        textureDesc.Height = _height;
        textureDesc.DepthOrArraySize = 1;
        textureDesc.MipLevels = 1;
        textureDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        textureDesc.SampleDesc.Count = 1;
        textureDesc.SampleDesc.Quality = 0;
        textureDesc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
        textureDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;

        D3D12_HEAP_PROPERTIES textureHeapProperties;
        textureHeapProperties.Type = D3D12_HEAP_TYPE_DEFAULT;
        textureHeapProperties.CPUPageProperty = D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
        textureHeapProperties.MemoryPoolPreference = D3D12_MEMORY_POOL_UNKNOWN;
        textureHeapProperties.CreationNodeMask = 1;
        textureHeapProperties.VisibleNodeMask = 1;

        D3D12_RESOURCE_DESC readbackBufferDesc;
        readbackBufferDesc.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
        readbackBufferDesc.Alignment = 0;
        readbackBufferDesc.Width = readbackBufferWidth(_width, _height);
        readbackBufferDesc.Height = 1;
        readbackBufferDesc.DepthOrArraySize = 1;
        readbackBufferDesc.MipLevels = 1;
        readbackBufferDesc.Format = DXGI_FORMAT_UNKNOWN;
        readbackBufferDesc.SampleDesc.Count = 1;
        readbackBufferDesc.SampleDesc.Quality = 0;
        readbackBufferDesc.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        readbackBufferDesc.Flags = D3D12_RESOURCE_FLAG_NONE;

        D3D12_HEAP_PROPERTIES readbackHeapProperties;
        readbackHeapProperties.Type = D3D12_HEAP_TYPE_READBACK;
        readbackHeapProperties.CPUPageProperty = D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
        readbackHeapProperties.MemoryPoolPreference = D3D12_MEMORY_POOL_UNKNOWN;
        readbackHeapProperties.CreationNodeMask = 1;
        readbackHeapProperties.VisibleNodeMask = 1;

        device->backendContext.fDevice->CreateCommittedResource(&textureHeapProperties, D3D12_HEAP_FLAG_NONE, &textureDesc, D3D12_RESOURCE_STATE_RENDER_TARGET, nullptr, IID_PPV_ARGS(&resource));
        device->backendContext.fDevice->CreateCommittedResource(&readbackHeapProperties, D3D12_HEAP_FLAG_NONE, &readbackBufferDesc, D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&readbackBufferResource));
    }

    ~DirectXOffScreenTexture() {
        if (resource) {
            resource->Release();
        }

        if (readbackBufferResource) {
            readbackBufferResource->Release();
        }
    }

    int readbackBufferWidth() {
        return readbackBufferWidth(width, height);
    }
private: 
    static int readbackBufferWidth(int width, int height) {
         return calculateRowPitch(width) * height;
    }
};


extern "C"
{

    bool isAdapterSupported2(JNIEnv *env, jobject redrawer, IDXGIAdapter1 *hardwareAdapter) {
        DXGI_ADAPTER_DESC1 desc;
        hardwareAdapter->GetDesc1(&desc);
        if ((desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0) {
            return false;
        }

        std::wstring tmp(desc.Description);
        std::string name(tmp.begin(), tmp.end());
        jstring jname = env->NewStringUTF(name.c_str());

        static jclass cls = (jclass) env->NewGlobalRef(env->FindClass("org/jetbrains/skiko/graphicapi/InternalDirectXApi"));
        static jmethodID method = env->GetMethodID(cls, "isAdapterSupported", "(Ljava/lang/String;)Z");

        return env->CallBooleanMethod(redrawer, method, jname);
    }

    // TODO: extract common code with directXRedrawer
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_chooseAdapter(
            JNIEnv *env, jobject redrawer, jint adapterPriority) {
        gr_cp<IDXGIFactory4> deviceFactory;
        if (!SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&deviceFactory)))) {
            return 0;
        }

        gr_cp<IDXGIFactory6> factory6;
        if (!SUCCEEDED(deviceFactory->QueryInterface(IID_PPV_ARGS(&factory6)))) {
            return 0;
        }

        for (UINT adapterIndex = 0;; ++adapterIndex) {
            IDXGIAdapter1 *adapter = nullptr;
            if (!SUCCEEDED(factory6->EnumAdapterByGpuPreference(adapterIndex, (DXGI_GPU_PREFERENCE) adapterPriority, IID_PPV_ARGS(&adapter)))) {
                break;
            }
            if (
                SUCCEEDED(D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, _uuidof(ID3D12Device), nullptr)) &&
                isAdapterSupported2(env, redrawer, adapter)
            ) {
                return toJavaPointer(adapter);
            } else {
                adapter->Release();
            }
        }

        return 0;
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_createDirectXOffscreenDevice(
        JNIEnv *env, jobject redrawer, jlong adapterPtr) {

        gr_cp<IDXGIFactory4> deviceFactory;
        if (!SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&deviceFactory)))) {
            return 0;
        }
        if (adapterPtr == 0) {
            return 0;
        }
        gr_cp<IDXGIAdapter1> adapter((IDXGIAdapter1 *) adapterPtr);

        D3D_FEATURE_LEVEL maxSupportedFeatureLevel = D3D_FEATURE_LEVEL_12_0;
        D3D_FEATURE_LEVEL featureLevels[] = {
            D3D_FEATURE_LEVEL_12_1,
            D3D_FEATURE_LEVEL_12_0
        };

        for (int i = 0; i < _countof(featureLevels); i++) {
            if (SUCCEEDED(D3D12CreateDevice(adapter.get(), featureLevels[i], _uuidof(ID3D12Device), nullptr))) {
                maxSupportedFeatureLevel = featureLevels[i];
                break;
            }
        }

        gr_cp<ID3D12Device> device;
        if (!SUCCEEDED(D3D12CreateDevice(adapter.get(), maxSupportedFeatureLevel, IID_PPV_ARGS(&device)))) {
            return 0;
        }

        // Create the command queue
        gr_cp<ID3D12CommandQueue> queue;
        D3D12_COMMAND_QUEUE_DESC queueDesc = {};
        queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
        queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;

        if (!SUCCEEDED(device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&queue)))) {
            return 0;
        }

        ID3D12CommandAllocator* commandAllocator;
        if (!SUCCEEDED(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&commandAllocator)))) {
            return 0;
        }

        ID3D12GraphicsCommandList* commandList;
        if (!SUCCEEDED(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, commandAllocator, nullptr, IID_PPV_ARGS(&commandList)))) {
            return 0;
        }

        ID3D12Fence* fence;
        if (!SUCCEEDED(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)))) {
            return 0;
        }

        HANDLE fenceEvent = CreateEventEx(nullptr, false, false, EVENT_ALL_ACCESS);
        if (!fenceEvent) {
            return 0;
        }

        DirectXOffscreenDevice *d3dDevice = new DirectXOffscreenDevice();
        d3dDevice->commandAllocator = commandAllocator;
        d3dDevice->commandList = commandList;
        d3dDevice->fence = fence;
        d3dDevice->fenceEvent = fenceEvent;
        d3dDevice->backendContext.fAdapter = adapter;
        d3dDevice->backendContext.fDevice = device;
        d3dDevice->backendContext.fQueue = queue;
        d3dDevice->backendContext.fProtectedContext = GrProtected::kNo;

        return toJavaPointer(d3dDevice);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_makeDirectXRenderTargetOffScreen(
            JNIEnv *env, jobject redrawer, jlong texturePtr) {
        DirectXOffScreenTexture *texture = fromJavaPointer<DirectXOffScreenTexture *>(texturePtr);
        ID3D12Resource* resource = texture->resource;

        GrD3DTextureResourceInfo texResInfo = {};
        texResInfo.fResource.retain(resource);
        texResInfo.fResourceState = D3D12_RESOURCE_STATE_COMMON;
        texResInfo.fFormat = DXGI_FORMAT_B8G8R8A8_UNORM;
        texResInfo.fSampleCount = 1;
        texResInfo.fLevelCount = 1;
        GrBackendRenderTarget* renderTarget = new GrBackendRenderTarget(
            GrBackendRenderTargets::MakeD3D(texture->width, texture->height, texResInfo)
        );
        return reinterpret_cast<jlong>(renderTarget);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_makeDirectXContext(
        JNIEnv *env, jobject redrawer, jlong devicePtr)
    {
        DirectXOffscreenDevice *d3dDevice = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        GrD3DBackendContext backendContext = d3dDevice->backendContext;
        return toJavaPointer(GrDirectContexts::MakeD3D(backendContext).release());
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_makeDirectXTexture(
        JNIEnv *env, jobject redrawer, jlong devicePtr, jlong oldTexturePtr, jint width, jint height) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        DirectXOffScreenTexture *oldTexture = fromJavaPointer<DirectXOffScreenTexture *>(oldTexturePtr);

        DirectXOffScreenTexture *texture;

        if (oldTexture == nullptr || oldTexture->width != width || oldTexture->height != height) {
            if (oldTexture != nullptr) {
                delete oldTexture;
            }
            texture = new DirectXOffScreenTexture(device, width, height);

            if (texture->resource == nullptr || texture->readbackBufferResource == nullptr) {
                delete texture;
                return 0;
            }
        } else {
            texture = oldTexture;
        }

        return toJavaPointer(texture);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_disposeDirectXTexture(
        JNIEnv *env, jobject redrawer, jlong texturePtr) {
        DirectXOffScreenTexture *texture = fromJavaPointer<DirectXOffScreenTexture *>(texturePtr);
        delete texture;
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_waitForCompletion(
            JNIEnv *env, jobject redrawer, jlong devicePtr, jlong texturePtr) {

        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);

        DirectXOffScreenTexture *texture = fromJavaPointer<DirectXOffScreenTexture *>(texturePtr);

        auto commandAllocator = device->commandAllocator;
        auto commandList = device->commandList;

        commandAllocator->Reset();
        commandList->Reset(commandAllocator, nullptr);

        D3D12_RESOURCE_BARRIER textureResourceBarrier;
        textureResourceBarrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        textureResourceBarrier.Transition.pResource = texture->resource;
        textureResourceBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
        textureResourceBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_SOURCE;
        textureResourceBarrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES ;
        textureResourceBarrier.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;

        commandList->ResourceBarrier(1, &textureResourceBarrier);

        D3D12_TEXTURE_COPY_LOCATION src = {};
        src.pResource = texture->resource;
        src.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        src.SubresourceIndex = 0;

        D3D12_TEXTURE_COPY_LOCATION dst = {};
        dst.pResource = texture->readbackBufferResource;
        dst.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        dst.PlacedFootprint.Offset = 0;
        dst.PlacedFootprint.Footprint.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        dst.PlacedFootprint.Footprint.Width = texture->width;
        dst.PlacedFootprint.Footprint.Height = texture->height;
        dst.PlacedFootprint.Footprint.Depth = 1;
        dst.PlacedFootprint.Footprint.RowPitch = calculateRowPitch(texture->width);

        D3D12_BOX srcBox = {0, 0, 0, static_cast<UINT>(texture->width), static_cast<UINT>(texture->height), 1};

        commandList->CopyTextureRegion(&dst, 0, 0, 0, &src, &srcBox);

        commandList->Close();

        ID3D12CommandList* commandLists[] = { commandList };
        device->backendContext.fQueue->ExecuteCommandLists(_countof(commandLists), commandLists);

        // Wait for the command list to finish executing; the readback buffer will be ready to read
        auto fence = device->fence;
        auto fenceEvent = device->fenceEvent;
        auto& fenceValue = device->fenceValue;

        fenceValue += 1;
        device->backendContext.fQueue->Signal(fence, fenceValue);

        if (fence->GetCompletedValue() < fenceValue) {
            fence->SetEventOnCompletion(fenceValue, fenceEvent);
            WaitForSingleObject(fenceEvent, INFINITE);
        }
    }

    JNIEXPORT jboolean JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_readPixels(
            JNIEnv *env, jobject redrawer, jlong texturePtr, jbyteArray byteArray) {
        jbyte *bytesPtr = env->GetByteArrayElements(byteArray, nullptr);

        DirectXOffScreenTexture *texture = fromJavaPointer<DirectXOffScreenTexture *>(texturePtr);

        auto rangeLength = texture->readbackBufferWidth();
        D3D12_RANGE readbackBufferRange{ 0, static_cast<SIZE_T>(rangeLength) };

        /*
         * TODO: memcpy from unaligned texture is not supported, line by line copy is very slow,
         *       write compute shader to copy texture to readback buffer with no RowPitch padding
         *       to support arbitary texture size
         */
        if (rangeLength != texture->width * texture->height * 4) {
            return false;
        }

        void *readbackBufferBytesPtr = nullptr;
        texture->readbackBufferResource->Map(
            0,
            &readbackBufferRange,
            &readbackBufferBytesPtr
        );

        if (!readbackBufferBytesPtr) {
            // Couldn't map readback buffer
            return false;
        }

        memcpy(bytesPtr, readbackBufferBytesPtr, rangeLength);

        D3D12_RANGE emptyRange{ 0, 0 };
        texture->readbackBufferResource->Unmap(0, &emptyRange);

        env->ReleaseByteArrayElements(byteArray, bytesPtr, 0);

        return true;
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_disposeDevice(
        JNIEnv *env, jobject redrawer, jlong devicePtr) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        delete device;
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_getTextureAlignment(
            JNIEnv *env, jobject redrawer) {
        return D3D12_TEXTURE_DATA_PITCH_ALIGNMENT;
    }

} // extern "C"

// ---------------------------------------------------------------------------
// JBR SharedTextures interop bridge (Windows).
//
// Producer: the Skia/D3D12 offscreen device above. Consumer: JBR's Java2D
// Direct3D 9Ex pipeline, which can only open *legacy* shared handles.
// D3D12 can only export NT handles, so a D3D11.1 device on the same adapter
// bridges the two: it opens the D3D12 texture + fence via NT handles,
// GPU-waits the producer fence, and copies into a D3D11_RESOURCE_MISC_SHARED
// (legacy-handle) texture that JBR wraps via SharedTextures.wrapTexture.
//
// Synchronization variant A-corr (pre-registered): a bounded CPU wait drains
// the bridge copy before the Java2D blit. The copy is a single GPU-GPU
// texture copy; this replaces the full-surface readback CPU wait of the
// software path. Variant A-perf (completion-armed, no CPU wait) is a
// follow-up under the same pre-registration.
// ---------------------------------------------------------------------------

#include <d3d11_4.h>

class DirectXInteropBridge {
public:
    ID3D11Device* dev11 = nullptr;
    ID3D11Device1* dev11_1 = nullptr;
    ID3D11Device5* dev11_5 = nullptr;
    ID3D11DeviceContext* ctx11 = nullptr;
    ID3D11DeviceContext4* ctx11_4 = nullptr;
    ID3D12Fence* sharedFence12 = nullptr;  // signalled on the D3D12 queue
    ID3D11Fence* sharedFence11 = nullptr;  // same fence, opened on D3D11
    ID3D11Fence* copyFence11 = nullptr;    // drains the bridge copy (A-corr)
    HANDLE copyFenceEvent = nullptr;
    UINT64 fenceValue = 0;
    UINT64 copyFenceValue = 0;
    HMODULE hD3D11 = nullptr;

    ~DirectXInteropBridge() {
        if (copyFenceEvent) CloseHandle(copyFenceEvent);
        if (copyFence11) copyFence11->Release();
        if (sharedFence11) sharedFence11->Release();
        if (sharedFence12) sharedFence12->Release();
        if (ctx11_4) ctx11_4->Release();
        if (ctx11) ctx11->Release();
        if (dev11_5) dev11_5->Release();
        if (dev11_1) dev11_1->Release();
        if (dev11) dev11->Release();
        if (hD3D11) FreeLibrary(hD3D11);
    }
};

class DirectXInteropSharedTexture {
public:
    int width = 0;
    int height = 0;
    ID3D12Resource* resource = nullptr;      // Skia renders into this
    ID3D11Texture2D* opened11 = nullptr;     // same resource, on the bridge
    // Legacy-shared consumer textures wrapped by JBR. Slot 0 is the only one
    // used in A-corr mode; A-perf rotates all three so the blit reads a copy
    // issued two frames ago (two frame intervals to complete) while the
    // current copy is in flight. The blit->overwrite gap stays one frame,
    // the same implicit-WDDM-sync window the two-slot variant relied on.
    static const int kLegacySlots = 3;
    ID3D11Texture2D* legacy11[kLegacySlots] = { nullptr, nullptr, nullptr };
    UINT64 slotCopyFenceValue[kLegacySlots] = { 0, 0, 0 };
    int currentSlot = 0;
    UINT64 pipelineStalls = 0;   // gate criterion: ~0 in steady state

    ~DirectXInteropSharedTexture() {
        for (int i = kLegacySlots - 1; i >= 0; i--) {
            if (legacy11[i]) legacy11[i]->Release();
        }
        if (opened11) opened11->Release();
        if (resource) resource->Release();
    }
};

extern "C"
{
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_createInteropBridge(
            JNIEnv *env, jobject obj, jlong devicePtr) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        if (device == nullptr) return 0;

        DirectXInteropBridge *bridge = new DirectXInteropBridge();

        // d3d11.dll is not linked by skiko: load dynamically
        typedef HRESULT (WINAPI *FnD3D11CreateDevice)(
            IDXGIAdapter*, D3D_DRIVER_TYPE, HMODULE, UINT,
            const D3D_FEATURE_LEVEL*, UINT, UINT,
            ID3D11Device**, D3D_FEATURE_LEVEL*, ID3D11DeviceContext**);
        bridge->hD3D11 = LoadLibraryW(L"d3d11.dll");
        FnD3D11CreateDevice createDevice11 = bridge->hD3D11 == nullptr ? nullptr :
            (FnD3D11CreateDevice)GetProcAddress(bridge->hD3D11, "D3D11CreateDevice");
        if (createDevice11 == nullptr) {
            delete bridge;
            return 0;
        }

        D3D_FEATURE_LEVEL fl = D3D_FEATURE_LEVEL_11_1;
        if (FAILED(createDevice11(device->backendContext.fAdapter.get(),
                                  D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0,
                                  &fl, 1, D3D11_SDK_VERSION,
                                  &bridge->dev11, nullptr, &bridge->ctx11)) ||
            FAILED(bridge->dev11->QueryInterface(IID_PPV_ARGS(&bridge->dev11_1))) ||
            FAILED(bridge->dev11->QueryInterface(IID_PPV_ARGS(&bridge->dev11_5))) ||
            FAILED(bridge->ctx11->QueryInterface(IID_PPV_ARGS(&bridge->ctx11_4))))
        {
            delete bridge;
            return 0;
        }

        // Shared producer fence: created on D3D12, opened on D3D11
        HANDLE hFence = nullptr;
        if (FAILED(device->backendContext.fDevice->CreateFence(
                0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(&bridge->sharedFence12))) ||
            FAILED(device->backendContext.fDevice->CreateSharedHandle(
                bridge->sharedFence12, nullptr, GENERIC_ALL, nullptr, &hFence)) ||
            FAILED(bridge->dev11_5->OpenSharedFence(hFence, IID_PPV_ARGS(&bridge->sharedFence11))))
        {
            if (hFence) CloseHandle(hFence);
            delete bridge;
            return 0;
        }
        CloseHandle(hFence);

        bridge->copyFenceEvent = CreateEventEx(nullptr, nullptr, 0, EVENT_ALL_ACCESS);
        if (bridge->copyFenceEvent == nullptr ||
            FAILED(bridge->dev11_5->CreateFence(0, D3D11_FENCE_FLAG_NONE,
                                                IID_PPV_ARGS(&bridge->copyFence11))))
        {
            delete bridge;
            return 0;
        }

        return toJavaPointer(bridge);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_disposeInteropBridge(
            JNIEnv *env, jobject obj, jlong bridgePtr) {
        DirectXInteropBridge *bridge = fromJavaPointer<DirectXInteropBridge *>(bridgePtr);
        delete bridge;
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_makeDirectXSharedTexture(
            JNIEnv *env, jobject obj, jlong devicePtr, jlong bridgePtr, jlong oldTexturePtr,
            jint width, jint height) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        DirectXInteropBridge *bridge = fromJavaPointer<DirectXInteropBridge *>(bridgePtr);
        DirectXInteropSharedTexture *oldTexture =
            fromJavaPointer<DirectXInteropSharedTexture *>(oldTexturePtr);
        if (device == nullptr || bridge == nullptr) return 0;

        if (oldTexture != nullptr && oldTexture->width == width && oldTexture->height == height) {
            return toJavaPointer(oldTexture);
        }
        delete oldTexture;

        DirectXInteropSharedTexture *tex = new DirectXInteropSharedTexture();
        tex->width = width;
        tex->height = height;

        D3D12_RESOURCE_DESC textureDesc = {};
        textureDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        textureDesc.Width = width;
        textureDesc.Height = height;
        textureDesc.DepthOrArraySize = 1;
        textureDesc.MipLevels = 1;
        textureDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        textureDesc.SampleDesc.Count = 1;
        textureDesc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
        textureDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET |
                            D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;

        D3D12_HEAP_PROPERTIES heapProps = {};
        heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;
        heapProps.CreationNodeMask = 1;
        heapProps.VisibleNodeMask = 1;

        HANDLE hTex = nullptr;
        if (FAILED(device->backendContext.fDevice->CreateCommittedResource(
                &heapProps, D3D12_HEAP_FLAG_SHARED, &textureDesc,
                D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&tex->resource))) ||
            FAILED(device->backendContext.fDevice->CreateSharedHandle(
                tex->resource, nullptr, GENERIC_ALL, nullptr, &hTex)) ||
            FAILED(bridge->dev11_1->OpenSharedResource1(hTex, IID_PPV_ARGS(&tex->opened11))))
        {
            if (hTex) CloseHandle(hTex);
            delete tex;
            return 0;
        }
        CloseHandle(hTex);

        D3D11_TEXTURE2D_DESC legacyDesc = {};
        legacyDesc.Width = width;
        legacyDesc.Height = height;
        legacyDesc.MipLevels = 1;
        legacyDesc.ArraySize = 1;
        legacyDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        legacyDesc.SampleDesc.Count = 1;
        legacyDesc.Usage = D3D11_USAGE_DEFAULT;
        legacyDesc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        legacyDesc.MiscFlags = D3D11_RESOURCE_MISC_SHARED; // legacy handle for D3D9Ex

        for (int i = 0; i < DirectXInteropSharedTexture::kLegacySlots; i++) {
            if (FAILED(bridge->dev11->CreateTexture2D(&legacyDesc, nullptr, &tex->legacy11[i]))) {
                delete tex;
                return 0;
            }
        }

        return toJavaPointer(tex);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_disposeDirectXSharedTexture(
            JNIEnv *env, jobject obj, jlong texturePtr) {
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        delete tex;
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_makeSharedTextureRenderTarget(
            JNIEnv *env, jobject obj, jlong texturePtr) {
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        if (tex == nullptr || tex->resource == nullptr) return 0;

        GrD3DTextureResourceInfo texResInfo = {};
        texResInfo.fResource.retain(tex->resource);
        texResInfo.fResourceState = D3D12_RESOURCE_STATE_COMMON;
        texResInfo.fFormat = DXGI_FORMAT_B8G8R8A8_UNORM;
        texResInfo.fSampleCount = 1;
        texResInfo.fLevelCount = 1;
        GrBackendRenderTarget* renderTarget = new GrBackendRenderTarget(
            GrBackendRenderTargets::MakeD3D(tex->width, tex->height, texResInfo)
        );
        return reinterpret_cast<jlong>(renderTarget);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_getLegacyD3D11TexturePtr(
            JNIEnv *env, jobject obj, jlong texturePtr) {
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        if (tex == nullptr) return 0;
        return (jlong)tex->legacy11[0];
    }

    // Debug-only (SKIKO_INTEROP_DEBUG=1): CPU-readback of the legacy-shared
    // texture after the bridge copy, to localize content loss.
    static void debugDumpLegacyTexture(DirectXInteropBridge *bridge,
                                       DirectXInteropSharedTexture *tex,
                                       UINT64 frame) {
        D3D11_TEXTURE2D_DESC desc = {};
        tex->legacy11[0]->GetDesc(&desc);
        desc.Usage = D3D11_USAGE_STAGING;
        desc.BindFlags = 0;
        desc.MiscFlags = 0;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        ID3D11Texture2D *staging = nullptr;
        if (FAILED(bridge->dev11->CreateTexture2D(&desc, nullptr, &staging))) {
            fprintf(stderr, "SKIKO_INTEROP_DEBUG frame=%llu staging-create-failed\n", frame);
            return;
        }
        bridge->ctx11->CopyResource(staging, tex->legacy11[0]);
        D3D11_MAPPED_SUBRESOURCE mapped = {};
        if (SUCCEEDED(bridge->ctx11->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
            const uint8_t *base = (const uint8_t*)mapped.pData;
            uint32_t corner = *(const uint32_t*)(base + 8 * mapped.RowPitch + 8 * 4);
            uint32_t center = *(const uint32_t*)(base + (tex->height / 2) * mapped.RowPitch
                                                 + (tex->width / 2) * 4);
            unsigned long long alphaSum = 0;
            for (int y = 0; y < tex->height; y += 16) {
                const uint8_t *row = base + y * mapped.RowPitch;
                for (int x = 0; x < tex->width; x += 16) {
                    alphaSum += row[x * 4 + 3];
                }
            }
            int samples = ((tex->height + 15) / 16) * ((tex->width + 15) / 16);
            fprintf(stderr,
                "SKIKO_INTEROP_DEBUG frame=%llu legacy corner=%08X center=%08X avgAlpha=%llu/255 (%dx%d)\n",
                frame, corner, center, samples ? alphaSum / samples : 0,
                tex->width, tex->height);
            fflush(stderr);
            bridge->ctx11->Unmap(staging, 0);
        } else {
            fprintf(stderr, "SKIKO_INTEROP_DEBUG frame=%llu map-failed\n", frame);
        }
        staging->Release();
    }

    // Signals the producer fence on the D3D12 queue, GPU-waits it on the
    // bridge, copies into the legacy-shared texture, and (A-corr) drains the
    // copy with a bounded CPU wait so the subsequent Java2D blit observes it.
    JNIEXPORT jboolean JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_bridgeCopyAndSync(
            JNIEnv *env, jobject obj, jlong devicePtr, jlong bridgePtr, jlong texturePtr) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        DirectXInteropBridge *bridge = fromJavaPointer<DirectXInteropBridge *>(bridgePtr);
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        if (device == nullptr || bridge == nullptr || tex == nullptr) return JNI_FALSE;

        bridge->fenceValue++;
        if (FAILED(device->backendContext.fQueue->Signal(bridge->sharedFence12, bridge->fenceValue))) {
            return JNI_FALSE;
        }
        if (FAILED(bridge->ctx11_4->Wait(bridge->sharedFence11, bridge->fenceValue))) {
            return JNI_FALSE;
        }
        bridge->ctx11->CopyResource(tex->legacy11[0], tex->opened11);
        bridge->ctx11->Flush();

        // A-corr: bounded CPU drain of the single texture copy
        bridge->copyFenceValue++;
        if (FAILED(bridge->ctx11_4->Signal(bridge->copyFence11, bridge->copyFenceValue))) {
            return JNI_FALSE;
        }
        if (bridge->copyFence11->GetCompletedValue() < bridge->copyFenceValue) {
            if (FAILED(bridge->copyFence11->SetEventOnCompletion(
                    bridge->copyFenceValue, bridge->copyFenceEvent))) {
                return JNI_FALSE;
            }
            if (WaitForSingleObject(bridge->copyFenceEvent, 1000) != WAIT_OBJECT_0) {
                return JNI_FALSE;
            }
        }

        static int debugEnabled = -1;
        if (debugEnabled == -1) {
            const char *dbg = getenv("SKIKO_INTEROP_DEBUG");
            debugEnabled = (dbg != nullptr && dbg[0] == '1') ? 1 : 0;
        }
        if (debugEnabled == 1 && (bridge->fenceValue == 1 || bridge->fenceValue % 120 == 0)) {
            debugDumpLegacyTexture(bridge, tex, bridge->fenceValue);
        }
        return JNI_TRUE;
    }

    // A-perf pipelined variant (triple-buffered): copies the producer texture
    // into the current slot's legacy texture WITHOUT waiting for that copy,
    // and returns the OLDEST slot's legacy texture (whose copy was issued two
    // frames ago, so it has had two frame intervals to complete) for the
    // Java2D blit. A bounded CPU wait happens only if that copy has not
    // completed yet (counted in pipelineStalls; the pre-registered gate
    // expects 0 in steady state). The two-slot variant blitted the copy
    // issued one frame earlier and stalled on 1.8-3.7% of frames; the third
    // slot doubles the completion window without widening the blit->overwrite
    // hazard (still one frame, covered by implicit WDDM sync on legacy
    // shared surfaces). Trades two frames of latency for a frame path with
    // no steady-state CPU wait.
    // Returns the ID3D11Texture2D* to blit, or 0 on failure.
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_bridgeCopyPipelined(
            JNIEnv *env, jobject obj, jlong devicePtr, jlong bridgePtr, jlong texturePtr) {
        DirectXOffscreenDevice *device = fromJavaPointer<DirectXOffscreenDevice *>(devicePtr);
        DirectXInteropBridge *bridge = fromJavaPointer<DirectXInteropBridge *>(bridgePtr);
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        if (device == nullptr || bridge == nullptr || tex == nullptr) return 0;

        const int slots = DirectXInteropSharedTexture::kLegacySlots;
        const int slot = tex->currentSlot;
        const int oldestSlot = (slot + 1) % slots;      // copy issued slots-1 frames ago
        const int prevSlot = (slot + slots - 1) % slots; // copy issued last frame

        // order: producer render (already submitted) -> bridge copy into slot
        bridge->fenceValue++;
        if (FAILED(device->backendContext.fQueue->Signal(bridge->sharedFence12, bridge->fenceValue))) {
            return 0;
        }
        if (FAILED(bridge->ctx11_4->Wait(bridge->sharedFence11, bridge->fenceValue))) {
            return 0;
        }
        bridge->ctx11->CopyResource(tex->legacy11[slot], tex->opened11);
        bridge->ctx11->Flush();
        bridge->copyFenceValue++;
        if (FAILED(bridge->ctx11_4->Signal(bridge->copyFence11, bridge->copyFenceValue))) {
            return 0;
        }
        tex->slotCopyFenceValue[slot] = bridge->copyFenceValue;

        // blit target: the oldest issued copy. During startup (or after
        // resize) older slots have no copy yet: fall back to the newest slot
        // that has one, down to the current slot (A-corr drain for that
        // frame only). Only a wait on a full-age copy counts as a stall.
        int blitSlot = oldestSlot;
        if (tex->slotCopyFenceValue[blitSlot] == 0) {
            blitSlot = tex->slotCopyFenceValue[prevSlot] != 0 ? prevSlot : slot;
        }
        UINT64 needed = tex->slotCopyFenceValue[blitSlot];
        if (bridge->copyFence11->GetCompletedValue() < needed) {
            if (blitSlot == oldestSlot) {
                tex->pipelineStalls++;
            }
            if (FAILED(bridge->copyFence11->SetEventOnCompletion(needed, bridge->copyFenceEvent))) {
                return 0;
            }
            if (WaitForSingleObject(bridge->copyFenceEvent, 1000) != WAIT_OBJECT_0) {
                return 0;
            }
        }

        tex->currentSlot = oldestSlot;
        return (jlong)tex->legacy11[blitSlot];
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_graphicapi_InternalDirectXApi_getPipelineStalls(
            JNIEnv *env, jobject obj, jlong texturePtr) {
        DirectXInteropSharedTexture *tex = fromJavaPointer<DirectXInteropSharedTexture *>(texturePtr);
        if (tex == nullptr) return -1;
        return (jlong)tex->pipelineStalls;
    }

} // extern "C"

#endif // SK_DIRECT3D
