#include "compute_gpu.h"
#include <vulkan/vulkan.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <map>
#include <sstream>
#include <stdexcept>

namespace bbcompute {
namespace {
template<class T> T vk(VkStructureType type){T value{};value.sType=type;return value;}
void check(VkResult value){if(value!=VK_SUCCESS)throw std::runtime_error("VULKAN_"+std::to_string(value));}
struct Params {std::uint32_t count,iterations,seed,kind,width,height,span,base;};
struct Plan {std::uint32_t local=64,count=16384,iterations=256;std::string calibration="[]";};
const char* shader(int kind){
    switch(kind){case MemoryRead:case MemoryWrite:case MemoryCopy:return "memory.comp.spv";case Fp32:return "arithmetic.comp.spv";case Fp64:return "arithmetic64.comp.spv";
    case Int24:case Int32:return "integer.comp.spv";case Int64:return "integer64.comp.spv";
    case Aes256:return "aes.comp.spv";case Sha1:return "sha1.comp.spv";case Julia:return "julia.comp.spv";case Mandel:return "mandel.comp.spv";default:throw std::invalid_argument("GPU_OPERATION");}
}
}
struct GpuEngine::Impl {
    AAssetManager* assets=nullptr;
    bool allowSoftware=false;
    VkInstance instance=VK_NULL_HANDLE;VkPhysicalDevice physical=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;VkQueue queue=VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};VkPhysicalDeviceFeatures features{};VkPhysicalDeviceMemoryProperties memory{};
    std::uint32_t queueIndex=0,timestampBits=0;bool broken=false;
    VkCommandPool commandPool=VK_NULL_HANDLE;VkCommandBuffer command=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;VkQueryPool queries=VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorLayout=VK_NULL_HANDLE;VkDescriptorPool descriptorPool=VK_NULL_HANDLE;VkDescriptorSet descriptor=VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout=VK_NULL_HANDLE;std::map<std::pair<int,unsigned>,VkPipeline> pipelines;
    struct Buffer{VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory allocation=VK_NULL_HANDLE;void* mapped=nullptr;bool coherent=false;VkDeviceSize bytes=0;std::uint32_t type=0,flags=0;};
    Buffer host,a,b;VkDeviceSize bytes=0;int bufferKind=-1;
    std::map<std::tuple<int,int,int,VkDeviceSize>,Plan> plans;
    void init(){
        auto app=vk<VkApplicationInfo>(VK_STRUCTURE_TYPE_APPLICATION_INFO);app.pApplicationName="BenchBridge";app.apiVersion=VK_API_VERSION_1_0;
        auto create=vk<VkInstanceCreateInfo>(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);create.pApplicationInfo=&app;check(vkCreateInstance(&create,nullptr,&instance));
        std::uint32_t count=0;check(vkEnumeratePhysicalDevices(instance,&count,nullptr));std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance,&count,devices.data()));int best=-1;
        for(auto candidate:devices){VkPhysicalDeviceProperties prop;vkGetPhysicalDeviceProperties(candidate,&prop);
            int priority=prop.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU?(allowSoftware?0:-1):prop.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU?3:prop.deviceType==VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU?2:1;
            if(priority>best){best=priority;physical=candidate;properties=prop;}}
        if(physical==VK_NULL_HANDLE)throw std::runtime_error("GPU_UNAVAILABLE");
        vkGetPhysicalDeviceFeatures(physical,&features);vkGetPhysicalDeviceMemoryProperties(physical,&memory);
        vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,nullptr);std::vector<VkQueueFamilyProperties> families(count);vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,families.data());
        bool found=false;for(unsigned i=0;i<count;++i)if(families[i].queueFlags&VK_QUEUE_COMPUTE_BIT){queueIndex=i;timestampBits=families[i].timestampValidBits;found=true;break;}
        if(!found)throw std::runtime_error("COMPUTE_QUEUE_UNAVAILABLE");
        float priority=1;auto q=vk<VkDeviceQueueCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);q.queueFamilyIndex=queueIndex;q.queueCount=1;q.pQueuePriorities=&priority;
        VkPhysicalDeviceFeatures enabled{};enabled.shaderFloat64=features.shaderFloat64;enabled.shaderInt64=features.shaderInt64;
        auto d=vk<VkDeviceCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);d.queueCreateInfoCount=1;d.pQueueCreateInfos=&q;d.pEnabledFeatures=&enabled;check(vkCreateDevice(physical,&d,nullptr,&device));vkGetDeviceQueue(device,queueIndex,0,&queue);
        auto pool=vk<VkCommandPoolCreateInfo>(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);pool.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pool.queueFamilyIndex=queueIndex;check(vkCreateCommandPool(device,&pool,nullptr,&commandPool));
        auto alloc=vk<VkCommandBufferAllocateInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);alloc.commandPool=commandPool;alloc.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;alloc.commandBufferCount=1;check(vkAllocateCommandBuffers(device,&alloc,&command));
        auto f=vk<VkFenceCreateInfo>(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);check(vkCreateFence(device,&f,nullptr,&fence));
        if(timestampBits){auto query=vk<VkQueryPoolCreateInfo>(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);query.queryType=VK_QUERY_TYPE_TIMESTAMP;query.queryCount=2;check(vkCreateQueryPool(device,&query,nullptr,&queries));}
        VkDescriptorSetLayoutBinding bindings[2]{};for(unsigned i=0;i<2;++i){bindings[i].binding=i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;bindings[i].descriptorCount=1;bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;}
        auto layout=vk<VkDescriptorSetLayoutCreateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);layout.bindingCount=2;layout.pBindings=bindings;check(vkCreateDescriptorSetLayout(device,&layout,nullptr,&descriptorLayout));
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,2};auto dsPool=vk<VkDescriptorPoolCreateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);dsPool.maxSets=1;dsPool.poolSizeCount=1;dsPool.pPoolSizes=&size;check(vkCreateDescriptorPool(device,&dsPool,nullptr,&descriptorPool));
        auto ds=vk<VkDescriptorSetAllocateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);ds.descriptorPool=descriptorPool;ds.descriptorSetCount=1;ds.pSetLayouts=&descriptorLayout;check(vkAllocateDescriptorSets(device,&ds,&descriptor));
        VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(Params)};auto pl=vk<VkPipelineLayoutCreateInfo>(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);pl.setLayoutCount=1;pl.pSetLayouts=&descriptorLayout;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&push;check(vkCreatePipelineLayout(device,&pl,nullptr,&pipelineLayout));
    }
    void freeBuffer(Buffer& value){if(value.mapped)vkUnmapMemory(device,value.allocation);if(value.buffer)vkDestroyBuffer(device,value.buffer,nullptr);if(value.allocation)vkFreeMemory(device,value.allocation,nullptr);value={};}
    ~Impl(){
        // 超时后由独立工作进程重启回收驱动，避免在析构中无限等待。
        // After a timeout the isolated worker is restarted instead of waiting forever in destruction.
        if(broken)return;
        if(device){vkDeviceWaitIdle(device);freeBuffer(host);freeBuffer(a);freeBuffer(b);
            for(const auto& entry:pipelines)vkDestroyPipeline(device,entry.second,nullptr);
            if(pipelineLayout)vkDestroyPipelineLayout(device,pipelineLayout,nullptr);
            if(descriptorPool)vkDestroyDescriptorPool(device,descriptorPool,nullptr);
            if(descriptorLayout)vkDestroyDescriptorSetLayout(device,descriptorLayout,nullptr);
            if(queries)vkDestroyQueryPool(device,queries,nullptr);if(fence)vkDestroyFence(device,fence,nullptr);
            if(commandPool)vkDestroyCommandPool(device,commandPool,nullptr);vkDestroyDevice(device,nullptr);}
        if(instance)vkDestroyInstance(instance,nullptr);
    }
    void makeBuffer(Buffer& buffer,VkDeviceSize size,VkMemoryPropertyFlags required,VkMemoryPropertyFlags preferred){
        buffer.bytes=size;
        auto info=vk<VkBufferCreateInfo>(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);info.size=size;info.usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;check(vkCreateBuffer(device,&info,nullptr,&buffer.buffer));
        VkMemoryRequirements req;vkGetBufferMemoryRequirements(device,buffer.buffer,&req);int choice=-1,score=-1;
        for(unsigned i=0;i<memory.memoryTypeCount;++i){auto flags=memory.memoryTypes[i].propertyFlags;if((req.memoryTypeBits&(1u<<i))&&(flags&required)==required){int rank=((flags&preferred&VK_MEMORY_PROPERTY_HOST_CACHED_BIT)?2:0)+((flags&preferred&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)?1:0);if(rank>score){choice=i;score=rank;}}}
        if(choice<0)throw std::runtime_error("GPU_MEMORY_TYPE");
        auto alloc=vk<VkMemoryAllocateInfo>(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);alloc.allocationSize=req.size;alloc.memoryTypeIndex=choice;check(vkAllocateMemory(device,&alloc,nullptr,&buffer.allocation));check(vkBindBufferMemory(device,buffer.buffer,buffer.allocation,0));
        auto flags=memory.memoryTypes[choice].propertyFlags;buffer.coherent=flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;buffer.type=choice;buffer.flags=flags;
        if(required&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)check(vkMapMemory(device,buffer.allocation,0,VK_WHOLE_SIZE,0,&buffer.mapped));
    }
    void buffers(int kind,std::uint64_t wanted){
        if(bytes==wanted&&bufferKind==kind&&host.buffer&&a.buffer&&b.buffer)return;
        freeBuffer(host);freeBuffer(a);freeBuffer(b);bytes=wanted;bufferKind=kind;
        const auto source=kind==MemoryRead||kind==Sha1?wanted:kind==MemoryCopy||kind==Aes256?wanted/2:16;
        const auto destination=kind==MemoryRead?4096:kind==Sha1?wanted/crypto_message_bytes*20:kind==MemoryCopy||kind==Aes256?wanted/2:wanted;
        if(source>properties.limits.maxStorageBufferRange||destination>properties.limits.maxStorageBufferRange)throw std::runtime_error("GPU_WORKSET_LIMIT");
        try{
            makeBuffer(host,std::max(source,destination),VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,VK_MEMORY_PROPERTY_HOST_COHERENT_BIT|VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
            makeBuffer(a,source,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,0);makeBuffer(b,destination,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,0);
        }catch(...){freeBuffer(host);freeBuffer(a);freeBuffer(b);bytes=0;throw;}
        VkDescriptorBufferInfo values[2]={{b.buffer,0,b.bytes},{a.buffer,0,a.bytes}};
        VkWriteDescriptorSet writes[2]{};for(unsigned i=0;i<2;++i){writes[i]=vk<VkWriteDescriptorSet>(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);writes[i].dstSet=descriptor;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&values[i];}
        vkUpdateDescriptorSets(device,2,writes,0,nullptr);
    }
    void visibility(bool flush){if(host.coherent)return;auto range=vk<VkMappedMemoryRange>(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE);range.memory=host.allocation;range.size=VK_WHOLE_SIZE;if(flush)check(vkFlushMappedMemoryRanges(device,1,&range));else check(vkInvalidateMappedMemoryRanges(device,1,&range));}
    std::uint64_t submit(const std::function<void()>& record,bool timed){
        check(vkResetCommandBuffer(command,0));auto begin=vk<VkCommandBufferBeginInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(vkBeginCommandBuffer(command,&begin));
        auto barrier=vk<VkMemoryBarrier>(VK_STRUCTURE_TYPE_MEMORY_BARRIER);barrier.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_MEMORY_READ_BIT;barrier.dstAccessMask=VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_MEMORY_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        if(timed&&queries){vkCmdResetQueryPool(command,queries,0,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);}
        record();
        if(timed&&queries)vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);
        check(vkEndCommandBuffer(command));check(vkResetFences(device,1,&fence));
        auto info=vk<VkSubmitInfo>(VK_STRUCTURE_TYPE_SUBMIT_INFO);info.commandBufferCount=1;info.pCommandBuffers=&command;
        const auto start=now_ns();auto result=vkQueueSubmit(queue,1,&info,fence);
        if(result!=VK_SUCCESS){broken=true;throw std::runtime_error("GPU_SUBMIT_FAILED");}
        do {result=vkWaitForFences(device,1,&fence,VK_TRUE,10000000);if(now_ns()-start>5000000000ull){broken=true;throw std::runtime_error("GPU_TIMEOUT");}}while(result==VK_TIMEOUT);
        if(result!=VK_SUCCESS){broken=true;throw std::runtime_error("GPU_DEVICE_LOST");}
        const auto wall=now_ns()-start;
        if(timed&&queries){std::uint64_t stamps[2];check(vkGetQueryPoolResults(device,queries,0,2,sizeof(stamps),stamps,sizeof(std::uint64_t),VK_QUERY_RESULT_64_BIT));
            const auto mask=timestampBits>=64?UINT64_MAX:((1ull<<timestampBits)-1);const auto elapsed=((stamps[1]-stamps[0])&mask)*double(properties.limits.timestampPeriod);
            if(!std::isfinite(elapsed)||elapsed<1||elapsed>5e9)throw std::runtime_error("GPU_TIMER_INVALID");return static_cast<std::uint64_t>(elapsed);}
        return wall;
    }
    std::uint64_t copy(Buffer& src,Buffer& dst,VkDeviceSize size,bool timed){return submit([&]{VkBufferCopy region{0,0,size};vkCmdCopyBuffer(command,src.buffer,dst.buffer,1,&region);},timed);}
    void clearOutput(){submit([&]{vkCmdFillBuffer(command,b.buffer,0,b.bytes,0xcdcdcdcdu);},false);}
    void prepare(int kind,std::atomic<bool>& cancelled){
        if(kind==MemoryRead||kind==MemoryCopy||kind==Aes256||kind==Sha1){
            for(std::size_t offset=0;offset<a.bytes;offset+=crypto_message_bytes){
                if(cancelled.load())throw std::runtime_error("RUN_CANCELLED");
                fill_data(static_cast<std::uint8_t*>(host.mapped)+offset,std::min<std::size_t>(crypto_message_bytes,a.bytes-offset),offset);
            }
            visibility(true);copy(host,a,a.bytes,false);
        }
        clearOutput();
    }
    VkPipeline pipeline(int kind,unsigned local){
        const auto key=std::pair(kind,local);if(pipelines.contains(key))return pipelines.at(key);
        if(!assets)throw std::runtime_error("SHADER_ASSETS_MISSING");
        const auto path=std::string("shaders/")+shader(kind);auto* asset=AAssetManager_open(assets,path.c_str(),AASSET_MODE_BUFFER);
        if(!asset)throw std::runtime_error("SHADER_MISSING:"+path);
        const auto length=AAsset_getLength(asset);
        if(length<=0||length%4||length>16*1024*1024){AAsset_close(asset);throw std::runtime_error("SHADER_SIZE");}
        std::vector<std::uint32_t> code(length/4);const auto read=AAsset_read(asset,code.data(),length);AAsset_close(asset);if(read!=length)throw std::runtime_error("SHADER_READ");
        auto moduleInfo=vk<VkShaderModuleCreateInfo>(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);moduleInfo.codeSize=length;moduleInfo.pCode=code.data();VkShaderModule module;check(vkCreateShaderModule(device,&moduleInfo,nullptr,&module));
        VkSpecializationMapEntry entry{0,0,sizeof(local)};VkSpecializationInfo spec{1,&entry,sizeof(local),&local};
        auto stage=vk<VkPipelineShaderStageCreateInfo>(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;stage.module=module;stage.pName="main";stage.pSpecializationInfo=&spec;
        auto ci=vk<VkComputePipelineCreateInfo>(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);ci.stage=stage;ci.layout=pipelineLayout;VkPipeline result;
        const auto status=vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&ci,nullptr,&result);vkDestroyShaderModule(device,module,nullptr);check(status);pipelines[key]=result;return result;
    }
    std::uint64_t dispatch(int kind,const Plan& plan,std::uint32_t seed,int width,int height,unsigned base=0,unsigned count=0){
        if(!count)count=plan.count;
        const bool crypto=kind==Aes256||kind==Sha1;
        const auto span=kind<=MemoryCopy?(kind==MemoryWrite?b.bytes:a.bytes)/16:crypto?a.bytes/units_per_item(kind,1):count;
        if(kind>MemoryCopy&&!crypto&&std::uint64_t(count)*(kind>=Julia?4:32)>b.bytes)throw std::runtime_error("GPU_OUTPUT_LIMIT");
        if(crypto&&std::uint64_t(base)+count>span)throw std::runtime_error("GPU_INPUT_LIMIT");
        auto pipe=pipeline(kind,plan.local);Params params{count,plan.iterations,seed,static_cast<unsigned>(kind),static_cast<unsigned>(width),static_cast<unsigned>(height),static_cast<unsigned>(span),base};
        return submit([&]{vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipe);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,1,&descriptor,0,nullptr);
            vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(params),&params);vkCmdDispatch(command,(count+plan.local-1)/plan.local,1,1);},true);
    }
    bool verify(int kind,const Plan& plan,std::uint32_t seed,int width,int height,std::uint64_t touched=0,std::atomic<bool>* cancelled=nullptr){
        const bool crypto=kind==Aes256||kind==Sha1;
        const std::size_t size=kind<=MemoryCopy||crypto?b.bytes:kind>=Julia?std::size_t(plan.count)*4:std::size_t(plan.count)*sizeof(Words);
        copy(b,host,size,false);visibility(false);
        auto* data=static_cast<const std::uint32_t*>(host.mapped);
        if(kind==MemoryRead){
            std::array<std::uint32_t,1024> expected{};
            for(std::size_t word=0;word<a.bytes/4;++word){
                if((word&65535)==0&&cancelled&&cancelled->load())return false;
                const auto group=((word/4)%plan.count)/plan.local;
                expected[group*4+word%4]^=pattern(static_cast<std::uint32_t>(word),data_seed);
            }
            return std::equal(expected.begin(),expected.end(),data);
        }
        if(kind==MemoryWrite||kind==MemoryCopy){
            for(unsigned n=0;n<1024;++n){
                const auto i=n==0?0:n==1?b.bytes/4-1:pattern(n,73)%(b.bytes/4);
                if(data[i]!=(kind==MemoryWrite?seed:pattern(i,data_seed)))return false;
            }
            return true;
        }
        if(crypto){
            const auto count=std::min<std::uint64_t>(touched?touched:plan.count,a.bytes/units_per_item(kind,1));
            if(!count)return false;
            std::vector<std::uint8_t> input(kind==Aes256?16:crypto_message_bytes);
            const auto samples=std::min<std::uint64_t>(count,kind==Aes256?1024:32);
            for(unsigned n=0;n<samples;++n){
                if(cancelled&&cancelled->load())return false;
                const auto id=n==0?0:n==1?count-1:pattern(n,73)%count;
                fill_data(input.data(),input.size(),id*input.size());
                if(kind==Aes256){
                    std::uint8_t expected[16];aes_block(input.data(),expected);
                    if(std::memcmp(expected,static_cast<const std::uint8_t*>(host.mapped)+id*16,16))return false;
                }else{
                    std::uint32_t expected[5];sha_message(input.data(),input.size(),expected,false);
                    if(!std::equal(expected,expected+5,data+id*5))return false;
                }
            }
            return true;
        }
        for(unsigned n=0;n<32;++n){const auto id=n==0?0:n==1?plan.count-1:pattern(n,17)%plan.count;
            if(kind>=Julia){const auto expected=fractal_pixel(kind,id,width,height,128);if(std::abs(int(expected)-int(data[id]))>(kind==Julia?2:0))return false;}
            else {Words actual;std::copy_n(data+id*8,8,actual.begin());if(!equal_output(kind,reference(kind,id,plan.iterations,seed),actual))return false;}}
        return true;
    }
    Plan tune(int kind,int width,int height,std::atomic<bool>& cancelled){
        const auto planKey=std::tuple(kind,width,height,bytes);
        if(plans.contains(planKey))return plans.at(planKey);
        Plan best;double score=-1;std::ostringstream trials;trials<<'[';bool first=true;
        for(unsigned local:{64u,128u,256u}){
            if(cancelled.load())throw std::runtime_error("RUN_CANCELLED");
            if(local>properties.limits.maxComputeWorkGroupInvocations||local>properties.limits.maxComputeWorkGroupSize[0])continue;
            const bool crypto=kind==Aes256||kind==Sha1;
            Plan plan;plan.local=local;plan.count=kind>=Julia?width*height:local*256;plan.iterations=kind>=Julia?128:kind<=MemoryCopy||crypto?1:256;
            const auto limit=crypto?std::min<std::uint64_t>(a.bytes/units_per_item(kind,1),std::uint64_t(properties.limits.maxComputeWorkGroupCount[0])*local):0;
            if(crypto)plan.count=std::min<std::uint64_t>(limit,kind==Aes256?8192:local);
            if((plan.count+local-1)/local>properties.limits.maxComputeWorkGroupCount[0])continue;
            auto time=dispatch(kind,plan,123,width,height);
            if(crypto){
                const unsigned quantum=kind==Aes256?crypto_message_bytes/16:local;
                const auto desired=static_cast<unsigned>(std::min<double>(limit,double(plan.count)*6000000/std::max<std::uint64_t>(time,1)));
                plan.count=std::min<std::uint64_t>(limit,std::max<unsigned>(std::min<std::uint64_t>(quantum,limit),desired/quantum*quantum));
            }else if(kind>MemoryCopy&&kind<Julia){
                plan.iterations=std::clamp<std::uint32_t>(static_cast<std::uint32_t>(std::min(8192.0,double(plan.iterations)*2000000/std::max<std::uint64_t>(time,1))),1,8192u);
            }
            auto t1=dispatch(kind,plan,157,width,height),t2=dispatch(kind,plan,193,width,height);
            if(!verify(kind,plan,193,width,height,0,&cancelled))throw std::runtime_error(cancelled.load()?"RUN_CANCELLED":"GPU_CALIBRATION_VERIFY_FAILED");
            const double units=kind<=MemoryCopy?double(bytes):kind>=Julia?double(width)*height:double(plan.count)*units_per_item(kind,plan.iterations);
            const double rate=units*2e9/(t1+t2);
            if(!first)trials<<',';first=false;trials<<"{\"local_size\":"<<local<<",\"invocations\":"<<plan.count<<",\"iterations\":"<<plan.iterations<<",\"work_units_per_dispatch\":"<<static_cast<std::uint64_t>(units)<<",\"elapsed_ns\":["<<t1<<','<<t2<<"]}";
            if(rate>score){score=rate;best=plan;}
        }
        if(score<0)throw std::runtime_error("GPU_WORKGROUP_UNAVAILABLE");trials<<']';best.calibration=trials.str();plans[planKey]=best;return best;
    }
};
GpuEngine::GpuEngine(AAssetManager* assets,bool allow_software):p(std::make_unique<Impl>()){p->assets=assets;p->allowSoftware=allow_software;p->init();}
GpuEngine::~GpuEngine()=default;
bool GpuEngine::poisoned()const{return p->broken;}
std::string GpuEngine::capabilities()const{
    std::ostringstream s;s<<"{\"supported\":true,\"name\":"<<quote(p->properties.deviceName)<<",\"vendor_id\":"<<p->properties.vendorID<<",\"device_id\":"<<p->properties.deviceID
        <<",\"device_type\":"<<p->properties.deviceType<<",\"api_version\":"<<p->properties.apiVersion<<",\"driver_version\":"<<p->properties.driverVersion
        <<",\"fp64\":"<<(p->features.shaderFloat64?"true":"false")<<",\"int64\":"<<(p->features.shaderInt64?"true":"false")
        <<",\"timestamp_bits\":"<<p->timestampBits<<",\"timestamp_period_ns\":"<<p->properties.limits.timestampPeriod<<",\"max_storage_range_bytes\":"<<p->properties.limits.maxStorageBufferRange<<'}';return s.str();
}
std::string GpuEngine::round(int kind,int memory_mib,int warmup_ms,int duration_ms,int width,int height,std::atomic<bool>& cancelled){
    if(kind<0||kind>Mandel||memory_mib<4||memory_mib>256||warmup_ms<0||warmup_ms>3000||duration_ms<50||duration_ms>10000||width<64||width>1024||height<64||height>1024)throw std::invalid_argument("COMPUTE_PARAMETERS");
    if((kind==Fp64||kind==Mandel)&&!p->features.shaderFloat64)return "{\"status\":\"UNSUPPORTED\",\"error\":\"GPU_FP64_UNSUPPORTED\"}";
    if(kind==Int64&&!p->features.shaderInt64)return "{\"status\":\"UNSUPPORTED\",\"error\":\"GPU_INT64_UNSUPPORTED\"}";
    if(p->broken)throw std::runtime_error("GPU_RESTART_REQUIRED");
    const bool crypto=kind==Aes256||kind==Sha1;
    p->buffers(kind,std::uint64_t(kind<=MemoryCopy||crypto?memory_mib:4)*1048576);
    p->prepare(kind,cancelled);
    const auto plan=p->tune(kind,width,height,cancelled);
    Result r;r.kind=kind;r.backend="vulkan-compute-v2";
    std::uint32_t seed=data_seed,cursor=0;
    const auto totalItems=crypto?p->a.bytes/units_per_item(kind,1):0;
    auto batch=[&](bool measured,std::uint64_t& deviceTime,std::uint64_t& invocations){
        const unsigned count=crypto?std::min<std::uint64_t>(plan.count,totalItems-cursor):plan.count;
        if(kind>MemoryCopy&&!crypto)++seed;
        const auto time=p->dispatch(kind,plan,seed,width,height,cursor,count);
        if(crypto)cursor=static_cast<unsigned>((cursor+count)%totalItems);
        if(measured){
            deviceTime+=time;invocations+=count;
            r.work+=kind<=MemoryCopy?p->bytes:kind>=Julia?std::uint64_t(width)*height:std::uint64_t(count)*units_per_item(kind,plan.iterations);
        }
    };
    std::uint64_t deviceTime=0,invocations=0,batches=0;
    auto warmEnd=now_ns()+std::uint64_t(warmup_ms)*1000000;
    while(!cancelled.load()&&now_ns()<warmEnd)batch(false,deviceTime,invocations);
    // 暖机结果不能充当正式测量的输出；传输和清空均在计时外。
    // Warmup output cannot stand in for measured work; readback and clearing are outside timing.
    if(!cancelled.load())p->clearOutput();cursor=0;
    const auto start=now_ns(),end=start+std::uint64_t(duration_ms)*1000000;
    while(!cancelled.load()&&now_ns()<end){batch(true,deviceTime,invocations);++batches;}
    r.elapsed=r.wall=now_ns()-start;r.interrupted=cancelled.load();r.verified=!r.interrupted&&batches>0&&r.elapsed>0;
    if(r.verified)r.verified=p->verify(kind,plan,seed,width,height,invocations,&cancelled);
    r.interrupted=cancelled.load();r.verified &= !r.interrupted;
    const auto auxiliary=kind==MemoryRead||kind==Sha1?p->b.bytes:0;
    std::ostringstream extra;extra<<",\"protocol\":\"gpgpu-v2\",\"timer_scope\":\"measurement-window\",\"batches\":"<<batches
        <<",\"buffer_bytes\":"<<p->bytes<<",\"working_set_bytes\":"<<(kind<=MemoryCopy||crypto?p->bytes:0)
        <<",\"input_bytes\":"<<(kind==MemoryWrite||(kind>MemoryCopy&&!crypto)?0:p->a.bytes)<<",\"output_bytes\":"<<p->b.bytes<<",\"auxiliary_bytes\":"<<auxiliary
        <<",\"local_size\":"<<plan.local<<",\"invocations\":"<<plan.count<<",\"completed_invocations\":"<<invocations
        <<",\"iterations_per_invocation\":"<<plan.iterations<<",\"width\":"<<width<<",\"height\":"<<height<<",\"frames\":"<<(kind>=Julia?batches:0)
        <<",\"message_bytes\":"<<(crypto?crypto_message_bytes:0)<<",\"messages_processed\":"<<(crypto?r.work/crypto_message_bytes:0)
        <<",\"fractal_iterations\":128,\"calibration\":"<<plan.calibration<<",\"input_prepared_before_timing\":true,\"measured_transfer_commands\":0"
        <<",\"device_elapsed_ns\":"<<deviceTime<<",\"device_timer\":"<<quote(p->timestampBits?"vulkan-timestamp":"host-submit-fence")
        <<",\"input_memory_type\":"<<p->a.type<<",\"input_memory_flags\":"<<p->a.flags<<",\"output_memory_type\":"<<p->b.type<<",\"output_memory_flags\":"<<p->b.flags
        <<",\"warmup_ms\":"<<warmup_ms<<",\"requested_duration_ms\":"<<duration_ms<<",\"gpu\":"<<capabilities();r.extra=extra.str();return r.json();
}
}
