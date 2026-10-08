#include "BenchCore.h"
#include "../../app/src/main/cpp/ram_kernels.h"
#include "../../app/src/main/cpp/compute/compute_cpu_kernels.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <numeric>
#include <stdexcept>
#include <thread>
#include <vector>
#if defined(_WIN32)
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <io.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <share.h>
#else
#include <pthread.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#endif
#if defined(__APPLE__)
#include <CommonCrypto/CommonCryptor.h>
#include <CommonCrypto/CommonDigest.h>
#include <os/proc.h>
#include <TargetConditionals.h>
#endif

struct BBSession { std::atomic<bool> cancel{false}; };
namespace {
using Clock=std::chrono::steady_clock;
uint64_t now() {return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count());}
uint64_t cpu_now() {
#if defined(_WIN32)
    FILETIME created{},exited{},kernel{},user{};
    if(!GetThreadTimes(GetCurrentThread(),&created,&exited,&kernel,&user))return 0;
    return ((uint64_t(kernel.dwHighDateTime)<<32|kernel.dwLowDateTime)+(uint64_t(user.dwHighDateTime)<<32|user.dwLowDateTime))*100;
#else
    timespec t{};if(clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t)!=0)return 0;
    return uint64_t(t.tv_sec)*1000000000+uint64_t(t.tv_nsec);
#endif
}
void priority(int qos) {
#if defined(__APPLE__)
    if(pthread_set_qos_class_self_np(qos==0?QOS_CLASS_USER_INITIATED:QOS_CLASS_BACKGROUND,0)!=0)throw std::runtime_error("qos");
#else
    (void)qos;
#endif
}
void check(BBSession* s) {if(s->cancel.load(std::memory_order_relaxed))throw 1;}
struct RNG {
    uint64_t state;
    uint64_t next(){auto x=(state+=UINT64_C(0x9e3779b97f4a7c15));x=(x^(x>>30))*UINT64_C(0xbf58476d1ce4e5b9);x=(x^(x>>27))*UINT64_C(0x94d049bb133111eb);return x^(x>>31);}
    uint64_t bounded(uint64_t n){const auto threshold=-n%n;uint64_t x;do{x=next();}while(x<threshold);return x%n;}
};
struct Chain {
    std::vector<uint32_t> storage;
    uint32_t* data=nullptr;
    uint32_t cursor=0;
    uint64_t nodes=0;
    Chain(BBSession* s,uint64_t bytes,int stride,uint64_t seed):storage(bytes/4) {
        data=storage.data();nodes=bytes/uint64_t(stride);const auto step=uint32_t(stride/4);RNG rng{seed};
        for(size_t i=0;i<storage.size();++i){if((i&8191)==0)check(s);storage[i]=uint32_t(rng.next());}
        std::vector<uint32_t> order(nodes);std::iota(order.begin(),order.end(),0);
        for(size_t i=order.size()-1;i>0;--i){if((i&8191)==0)check(s);std::swap(order[i],order[rng.bounded(i+1)]);}
        for(size_t i=0;i<order.size();++i)data[order[i]*step]=order[(i+1)%order.size()]*step;
        cursor=order.front()*step;const auto origin=cursor;
        for(uint64_t i=0;i<nodes;++i){if((i&8191)==0)check(s);cursor=data[cursor];if(cursor>=bytes/4||cursor%step||(cursor==origin&&i+1!=nodes))throw 4;}
        if(cursor!=origin)throw 4;
    }
    uint64_t chase(BBSession* s,int ms,uint64_t minimum=0) {
        const auto begin=now();uint64_t count=0;
        do{check(s);cursor=bb_chase_index(data,cursor,4096);count+=4096;}while(now()-begin<uint64_t(ms)*1000000||count<minimum);
        return count;
    }
};
BBResult latency(BBSession* s,uint64_t bytes,int stride,int qos,uint64_t seed,int warm,int duration,int repeats) {
    BBResult out{};out.kind=5;out.threads=1;out.qos=qos;out.working_set_bytes=bytes;out.node_stride_bytes=stride;
    if(!s||bytes<4096||bytes>256ULL*1048576||bytes%256||stride<32||stride>256||(stride&(stride-1))||qos<0||qos>1||duration<5||duration>5000||warm<0||warm>5000){out.status=2;return out;}
    const auto wall=now();
    std::thread worker([&]{try{
        priority(qos);Chain chain(s,bytes,stride,seed);
        out.warmup_operations=chain.chase(s,warm,2*chain.nodes);
        for(int i=0;i<repeats;++i){
            auto& t=out.trials[i];const auto start=now(),cpu=cpu_now();t.operations=chain.chase(s,duration);
            t.cpu_ns=cpu_now()-cpu;t.elapsed_ns=now()-start;t.accepted=t.elapsed_ns<=uint64_t(duration)*1500000+5000000&&(t.cpu_ns==0||double(t.cpu_ns)/double(t.elapsed_ns)>=0.90);
            out.trial_count=i+1;
        }
        check(s);out.checksum=chain.cursor;out.verified=1;
    }catch(int error){out.status=error;}catch(const std::bad_alloc&){out.status=3;}catch(...){out.status=4;}});
    worker.join();out.wall_ns=now()-wall;return out;
}
struct File {
    int fd=-1;const char* path;bool created=false;
    explicit File(const char* p):path(p) {
#if defined(_WIN32)
        if(_sopen_s(&fd,path,_O_CREAT|_O_EXCL|_O_RDWR|_O_BINARY,_SH_DENYRW,_S_IREAD|_S_IWRITE)!=0)throw 5;
#else
        fd=open(path,O_CREAT|O_EXCL|O_RDWR,0600);
#endif
        if(fd<0)throw 5;created=true;
    }
    ~File(){if(fd>=0){
#if defined(_WIN32)
        _close(fd);
#else
        close(fd);
#endif
    }if(created)std::remove(path);}
    void seek(uint64_t offset){
#if defined(_WIN32)
        if(_lseeki64(fd,int64_t(offset),SEEK_SET)<0)throw 5;
#else
        if(lseek(fd,off_t(offset),SEEK_SET)<0)throw 5;
#endif
    }
    void io(bool write,void* buffer,size_t bytes){
        size_t done=0;while(done<bytes){
#if defined(_WIN32)
            const auto n=write?_write(fd,static_cast<char*>(buffer)+done,unsigned(bytes-done)):_read(fd,static_cast<char*>(buffer)+done,unsigned(bytes-done));
#else
            const auto n=write ? ::write(fd,static_cast<char*>(buffer)+done,bytes-done) : ::read(fd,static_cast<char*>(buffer)+done,bytes-done);
#endif
            if(n<=0)throw 5;done+=size_t(n);
        }
    }
    void sync(){
#if defined(_WIN32)
        if(_commit(fd)!=0)throw 5;
#else
        if(fsync(fd)!=0)throw 5;
#endif
    }
};
}
extern "C" BBSession* bb_session_create(){try{return new BBSession;}catch(...){return nullptr;}}
extern "C" void bb_session_cancel(BBSession* s){if(s)s->cancel.store(true);}
extern "C" int32_t bb_session_cancelled(BBSession* s){return !s||s->cancel.load();}
extern "C" void bb_session_destroy(BBSession* s){delete s;}
extern "C" const char* bb_core_protocol(){return "apple-unpinned-index-v1";}
extern "C" uint64_t bb_available_memory(){
#if defined(__APPLE__) && TARGET_OS_IPHONE
    return os_proc_available_memory();
#elif defined(_WIN32)
    MEMORYSTATUSEX state{};state.dwLength=sizeof(state);return GlobalMemoryStatusEx(&state)?state.ullAvailPhys:0;
#else
    return 0;
#endif
}
extern "C" int32_t bb_validate_chain(uint64_t bytes,int32_t stride,uint64_t seed){
    if(bytes<4096||bytes>256ULL*1048576||bytes%256||stride<32||stride>256||(stride&(stride-1)))return 0;
    try{BBSession s;Chain c(&s,bytes,stride,seed);return c.nodes==bytes/uint64_t(stride);}catch(...){return 0;}
}
extern "C" BBResult bb_cache_point(BBSession* s,uint64_t bytes,int32_t stride,int32_t qos,uint64_t seed)try{return latency(s,bytes,stride,qos,seed,40,30,7);}catch(...){BBResult r{};r.status=3;return r;}
extern "C" BBResult bb_memory(BBSession* s,int32_t kind,uint64_t bytes,int32_t threads,int32_t warm,int32_t duration,int32_t qos,uint64_t seed)try{
    if(kind==5){if(threads!=1){BBResult invalid{};invalid.status=2;return invalid;}return latency(s,bytes,64,qos,seed,warm,duration,1);}
    BBResult out{};out.kind=kind;out.threads=threads;out.qos=qos;
    if(!s||kind<0||kind>2||bytes<4096||bytes>512ULL*1048576||bytes%256||threads<1||threads>8||duration<5||duration>5000||warm<0||warm>5000||qos<0||qos>1){out.status=2;return out;}
    const uint64_t per=bytes/uint64_t(threads)/256*256;out.working_set_bytes=per*uint64_t(threads);
    if(per<4096){out.status=2;return out;}
    const auto wall=now();
    std::mutex mutex;std::condition_variable ready;int arrived=0,finished=0;bool go=false,verifyGo=false;uint64_t measureStart=0,measureEnd=0;
    std::vector<BBTrial> samples(static_cast<size_t>(threads));std::vector<int> errors(size_t(threads),0);std::vector<uint64_t> checksums(size_t(threads),0);
    std::vector<std::thread> workers;
    try {
        for(int id=0;id<threads;++id)workers.emplace_back([&,id]{bool measured=false;try{
            priority(qos);check(s);const auto words=size_t(per/8/(kind==2?2:1));const uint64_t value=seed+uint64_t(id)+1;
            std::vector<uint64_t> data(words,value),copy(kind==2?words:0,0);uint64_t sum=0;
            auto one=[&]{check(s);if(kind==0)sum^=bb_seq_read(data.data(),words);else if(kind==1)bb_seq_write(data.data(),words,value);else bb_copy(copy.data(),data.data(),words);};
            const auto warmStart=now();do{one();}while(now()-warmStart<uint64_t(warm)*1000000);
            sum=0;
            {std::unique_lock guard(mutex);++arrived;ready.notify_all();ready.wait(guard,[&]{return go;});}
            const auto begin=now(),cpu=cpu_now();uint64_t passes=0;
            do{one();++passes;}while(now()-begin<uint64_t(duration)*1000000);
            auto& t=samples[size_t(id)];t.cpu_ns=cpu_now()-cpu;t.elapsed_ns=now()-begin;t.operations=passes*words;t.logical_bytes=passes*words*8*(kind==2?2:1);t.accepted=1;
            // 所有线程结束计时后再校验，防止校验流量干扰其他线程。 / Verify only after every worker stops timing, avoiding cross-worker verification traffic.
            {std::unique_lock guard(mutex);++finished;measured=true;ready.notify_all();ready.wait(guard,[&]{return verifyGo;});}
            const auto& verified=kind==2?copy:data;
            if(!std::all_of(verified.begin(),verified.end(),[&](uint64_t x){return x==value;}))throw 4;
            if(kind==0 && sum!=(passes%2?value*words:0))throw 4;
            checksums[size_t(id)]=bb_seq_read(verified.data(),words);
        }catch(int error){errors[size_t(id)]=error;{std::lock_guard guard(mutex);if(!go)++arrived;if(!measured)++finished;}ready.notify_all();}
         catch(const std::bad_alloc&){errors[size_t(id)]=3;{std::lock_guard guard(mutex);if(!go)++arrived;if(!measured)++finished;}ready.notify_all();}
         catch(...){errors[size_t(id)]=4;{std::lock_guard guard(mutex);if(!go)++arrived;if(!measured)++finished;}ready.notify_all();}});
        {std::unique_lock guard(mutex);ready.wait(guard,[&]{return arrived==threads;});measureStart=now();go=true;ready.notify_all();ready.wait(guard,[&]{return finished==threads;});measureEnd=now();verifyGo=true;ready.notify_all();}
    }catch(...){out.status=3;{std::lock_guard guard(mutex);go=true;verifyGo=true;}ready.notify_all();}
    for(auto& worker:workers)worker.join();
    auto& total=out.trials[0];for(int i=0;i<threads;++i){if(errors[size_t(i)]!=0)out.status=errors[size_t(i)];const auto& t=samples[size_t(i)];total.elapsed_ns=std::max(total.elapsed_ns,t.elapsed_ns);total.cpu_ns+=t.cpu_ns;total.operations+=t.operations;total.logical_bytes+=t.logical_bytes;out.checksum^=checksums[size_t(i)];}
    total.elapsed_ns=measureEnd-measureStart;
    if(bb_session_cancelled(s))out.status=1;out.verified=out.status==0;out.trial_count=out.verified?1:0;total.accepted=out.verified;out.wall_ns=now()-wall;return out;
}catch(...){BBResult r{};r.status=3;return r;}
extern "C" BBResult bb_storage(BBSession* s,const char* path,int32_t writeTest,int32_t random,uint64_t bytes,int32_t block,int32_t duration){
    BBResult out{};out.kind=writeTest;out.threads=1;out.working_set_bytes=bytes;
    if(!s||!path||!path[0]||bytes<1048576||bytes>256ULL*1048576||block<4096||block>1048576||bytes%uint64_t(block)||duration<5||duration>5000||writeTest<0||writeTest>1||random<0||random>1){out.status=2;return out;}
    const auto wall=now();try{
        check(s);File file(path);RNG rng{419};std::vector<uint64_t> pattern(size_t(block/8)),buffer(size_t(block/8));for(auto& x:pattern)x=rng.next();
#if defined(__APPLE__)
        out.no_cache=fcntl(file.fd,F_NOCACHE,1)==0;
#endif
        constexpr uint64_t marker=UINT64_C(0xB16B4190C0FFEE);
        for(uint64_t offset=0;offset<bytes;offset+=uint64_t(block)){check(s);pattern[0]=offset^marker;file.io(true,pattern.data(),size_t(block));}file.sync();file.seek(0);
        auto& t=out.trials[0];const auto start=now(),cpu=cpu_now();uint64_t offset=0,last=0;
        do {check(s);if(random)offset=rng.bounded(bytes/uint64_t(block))*uint64_t(block);file.seek(offset);last=offset;
            if(writeTest)pattern[0]=offset^marker;
            file.io(writeTest!=0,writeTest?pattern.data():buffer.data(),size_t(block));t.operations++;t.logical_bytes+=uint64_t(block);offset=(offset+uint64_t(block))%bytes;
        }while(now()-start<uint64_t(duration)*1000000);
        if(writeTest)file.sync();t.cpu_ns=cpu_now()-cpu;t.elapsed_ns=now()-start;
        pattern[0]=last^marker;if(!writeTest&&buffer!=pattern)throw 4;
        file.seek(last);file.io(false,buffer.data(),size_t(block));check(s);if(buffer!=pattern)throw 4;
        t.accepted=1;out.verified=1;out.trial_count=1;out.checksum=buffer.front();
    }catch(int error){out.status=error;}catch(const std::bad_alloc&){out.status=3;}catch(...){out.status=5;}
    out.wall_ns=now()-wall;return out;
}
extern "C" int32_t bb_compute_reference(int32_t kind,uint32_t item,uint32_t iterations,uint32_t* words){
    if(!words||kind<3||kind>7||iterations>100000)return 0;try{const auto reference=bbcompute::reference(kind,item,iterations,419);std::copy(reference.begin(),reference.end(),words);return 1;}catch(...){return 0;}
}
extern "C" int32_t bb_check_compute(int32_t kind,uint32_t item,uint32_t iterations,const uint32_t* words){
    if(!words||kind<3||kind>7||iterations>100000)return 0;try{bbcompute::Words actual{};std::copy_n(words,8,actual.begin());return bbcompute::equal_output(kind,bbcompute::reference(kind,item,iterations,419),actual);}catch(...){return 0;}
}
extern "C" uint32_t bb_fractal_reference(int32_t kind,uint32_t pixel,uint32_t width,uint32_t height){if(width<2||height<2)return 0;return bbcompute::fractal_pixel(kind,pixel,width,height,128);}
extern "C" BBResult bb_cpu_compute(BBSession* s,int32_t kind,int32_t duration){
    BBResult out{};out.kind=kind;out.threads=1;
    if(!s||kind<3||kind>11||duration<5||duration>5000){out.status=2;return out;}
    const auto wall=now();try{
        check(s);uint32_t id=0;bbcompute::Words output{};constexpr uint32_t iterations=2048;
        if(kind>=3&&kind<=7){bbcompute::cpu_kernel(kind,id,iterations,419,output);if(!bb_check_compute(kind,id,iterations,output.data()))throw 4;}
        std::vector<uint32_t> pixels(kind>=10?64*64:0);
#if defined(__APPLE__)
        std::vector<uint8_t> input(kind==8||kind==9?65536:0,0x51),encoded(input.size());uint8_t key[32];std::iota(key,key+32,uint8_t(0));
        unsigned char digest[CC_SHA1_DIGEST_LENGTH]{};
#else
        if(kind==8||kind==9){out.status=6;return out;}
#endif
        auto& t=out.trials[0];const auto start=now(),cpu=cpu_now();
        do {check(s);
            if(kind<=7){bbcompute::cpu_kernel(kind,id,iterations,419,output);t.operations+=bbcompute::units_per_item(kind,iterations);}
            else if(kind>=10){bbcompute::cpu_frame(kind,64,64,pixels.data(),s->cancel);t.operations+=4096;}
#if defined(__APPLE__)
            else if(kind==8){size_t written=0;if(CCCrypt(kCCEncrypt,kCCAlgorithmAES,kCCOptionECBMode,key,32,nullptr,input.data(),input.size(),encoded.data(),encoded.size(),&written)!=kCCSuccess||written!=input.size())throw 4;t.operations+=written;}
            else {
                // SHA-1 仅作为既有基准项目；不用于安全用途。 / SHA-1 is retained only as a benchmark, never for security.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
                CC_SHA1(input.data(),CC_LONG(input.size()),digest);t.operations+=input.size();
#pragma clang diagnostic pop
            }
#endif
        }while(now()-start<uint64_t(duration)*1000000);
        t.cpu_ns=cpu_now()-cpu;t.elapsed_ns=now()-start;check(s);
        if(kind<=7&&!bb_check_compute(kind,id,iterations,output.data()))throw 4;
        if(kind>=10)for(uint32_t i=0;i<4096;i+=127)if(pixels[i]!=bbcompute::fractal_pixel(kind,i,64,64,128))throw 4;
#if defined(__APPLE__)
        if(kind==8){size_t written=0;std::vector<uint8_t> decoded(input.size());if(CCCrypt(kCCDecrypt,kCCAlgorithmAES,kCCOptionECBMode,key,32,nullptr,encoded.data(),encoded.size(),decoded.data(),decoded.size(),&written)!=kCCSuccess||decoded!=input)throw 4;}
        if(kind==9){const unsigned char expected[]={0x8d,0x41,0xbb,0x26,0x84,0x23,0xac,0x6b,0xfd,0x52,0xb1,0xc9,0x5f,0xc5,0x52,0x65,0x68,0x29,0x91,0x00};if(std::memcmp(digest,expected,20)!=0)throw 4;}
#endif
        out.verified=1;out.trial_count=1;t.accepted=1;out.checksum=output[0];
    }catch(int error){out.status=error;}catch(...){out.status=4;}out.wall_ns=now()-wall;return out;
}
