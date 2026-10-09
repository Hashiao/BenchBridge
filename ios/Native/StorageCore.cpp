#include "BenchCore.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cerrno>
#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#else
#include <aio.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#endif

namespace {
constexpr uint64_t PoolBytes=64ULL*1048576, Seed=0xB16B00B5ULL;
uint64_t clockNs(){return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());}
struct Failure { int status, error; };
void ioError(){
#if defined(_WIN32)
    throw Failure{5,int(GetLastError())};
#else
    throw Failure{5,errno};
#endif
}
void check(BBSession* s){if(bb_session_cancelled(s))throw Failure{1,0};}
uint64_t mix(uint64_t z){z=(z^(z>>30))*0xbf58476d1ce4e5b9ULL;z=(z^(z>>27))*0x94d049bb133111ebULL;return z^(z>>31);}
struct Random {
    uint64_t state;
    uint64_t next(){state+=0x9e3779b97f4a7c15ULL;return mix(state);}
    uint64_t bounded(uint64_t n){const uint64_t threshold=-n%n;uint64_t v;do{v=next();}while(v<threshold);return v%n;}
};
struct File {
#if defined(_WIN32)
    HANDLE fd=INVALID_HANDLE_VALUE;
#else
    int fd=-1;
#endif
    bool noCache=false;
    File(const std::string& path,bool create,bool bypass){
#if defined(_WIN32)
        fd=CreateFileA(path.c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,
                       create?CREATE_NEW:OPEN_EXISTING,FILE_FLAG_OVERLAPPED,nullptr);
        if(fd==INVALID_HANDLE_VALUE)ioError();(void)bypass;
#else
        fd=open(path.c_str(),O_RDWR|(create?(O_CREAT|O_EXCL):0),0600);if(fd<0)ioError();
#if defined(__APPLE__)
        if(bypass){if(fcntl(fd,F_NOCACHE,1)!=0){const int code=errno;close(fd);fd=-1;throw Failure{5,code};}noCache=true;}
#else
        (void)bypass;
#endif
#endif
    }
    ~File(){
#if defined(_WIN32)
        if(fd!=INVALID_HANDLE_VALUE)CloseHandle(fd);
#else
        if(fd>=0)close(fd);
#endif
    }
    void sync(){
#if defined(_WIN32)
        if(!FlushFileBuffers(fd))ioError();
#else
        if(fsync(fd)!=0)ioError();
#endif
    }
    void transfer(bool write,void* buffer,size_t bytes,uint64_t offset){
        size_t done=0;while(done<bytes){
#if defined(_WIN32)
            OVERLAPPED op{};const auto position=offset+done;op.Offset=DWORD(position);op.OffsetHigh=DWORD(position>>32);
            op.hEvent=CreateEventA(nullptr,TRUE,FALSE,nullptr);if(!op.hEvent)ioError();DWORD count=0;
            const bool ok=write?WriteFile(fd,static_cast<char*>(buffer)+done,DWORD(bytes-done),nullptr,&op):ReadFile(fd,static_cast<char*>(buffer)+done,DWORD(bytes-done),nullptr,&op);
            if(!ok&&GetLastError()!=ERROR_IO_PENDING){const auto code=GetLastError();CloseHandle(op.hEvent);throw Failure{5,int(code)};}
            const bool complete=GetOverlappedResult(fd,&op,&count,TRUE)!=0;const auto code=GetLastError();CloseHandle(op.hEvent);
            if(!complete||count==0)throw Failure{5,int(code)};done+=count;
#else
            const auto count=write?pwrite(fd,static_cast<char*>(buffer)+done,bytes-done,off_t(offset+done)):
                                   pread(fd,static_cast<char*>(buffer)+done,bytes-done,off_t(offset+done));
            if(count<0&&errno==EINTR)continue;if(count<=0)ioError();done+=size_t(count);
#endif
        }
    }
};
struct Slot {
    std::vector<uint64_t> buffer;
    bool active=false, completed=false;
    uint64_t offset=0;
#if defined(_WIN32)
    OVERLAPPED op{};
    explicit Slot(size_t bytes):buffer(bytes/8){op.hEvent=CreateEventA(nullptr,TRUE,FALSE,nullptr);if(!op.hEvent)ioError();}
    ~Slot(){CloseHandle(op.hEvent);}
    bool submit(File& f,bool write){ResetEvent(op.hEvent);op.Offset=DWORD(offset);op.OffsetHigh=DWORD(offset>>32);
        const bool ok=write?WriteFile(f.fd,buffer.data(),DWORD(buffer.size()*8),nullptr,&op):ReadFile(f.fd,buffer.data(),DWORD(buffer.size()*8),nullptr,&op);
        if(!ok&&GetLastError()!=ERROR_IO_PENDING)ioError();active=true;completed=false;return true;}
    bool finish(File& f,bool wait){DWORD count=0;if(!GetOverlappedResult(f.fd,&op,&count,wait)){
        const auto code=GetLastError();if(!wait&&code==ERROR_IO_INCOMPLETE)return false;active=false;throw Failure{5,int(code)};}
        active=false;if(count!=buffer.size()*8)throw Failure{5,0};completed=true;return true;}
#else
    aiocb op{};
    explicit Slot(size_t bytes):buffer(bytes/8){}
    bool submit(File& f,bool write){op={};op.aio_fildes=f.fd;op.aio_offset=off_t(offset);op.aio_buf=buffer.data();op.aio_nbytes=buffer.size()*8;op.aio_sigevent.sigev_notify=SIGEV_NONE;
        if((write?aio_write(&op):aio_read(&op))!=0){if(errno==EAGAIN)return false;ioError();}active=true;completed=false;return true;}
    bool finish(File&,bool wait){int code;while((code=aio_error(&op))==EINPROGRESS){if(!wait)return false;const aiocb* list[]={&op};timespec limit{0,10000000};aio_suspend(list,1,&limit);}
        const auto count=aio_return(&op);active=false;if(code!=0||count!=ssize_t(buffer.size()*8))throw Failure{5,code};completed=true;return true;}
#endif
};
struct WorkerResult {uint64_t operations=0,ended=0,startDelay=0;double queueArea=0;int maximum=0;bool limited=false;};
}
struct BBStorage {
    BBSession* session;std::string path;uint64_t bytes,written=0;bool bypass,owned=false;
    std::vector<uint64_t> pattern;std::unique_ptr<File> file;
    BBStorage(BBSession* s,const char* p,uint64_t n,bool cache):session(s),path(p),bytes(n),bypass(cache){}
    ~BBStorage(){file.reset();if(owned)std::remove(path.c_str());}
    void fill(void* output,uint64_t offset,size_t count)const{
        auto* target=static_cast<char*>(output);while(count){const auto at=size_t(offset%PoolBytes),part=std::min(count,size_t(PoolBytes)-at);
            std::memcpy(target,reinterpret_cast<const char*>(pattern.data())+at,part);target+=part;offset+=part;count-=part;}
    }
    bool valid(const void* input,uint64_t offset,size_t count)const{
        const auto* source=static_cast<const char*>(input);while(count){const auto at=size_t(offset%PoolBytes),part=std::min(count,size_t(PoolBytes)-at);
            if(std::memcmp(source,reinterpret_cast<const char*>(pattern.data())+at,part))return false;source+=part;offset+=part;count-=part;}return true;
    }
};
extern "C" BBStorage* bb_storage_create(BBSession* s,const char* path,uint64_t bytes,int32_t bypass,BBStorageResult* result){
    if(!result)return nullptr;*result={};result->error_phase=1;const auto start=clockNs();
    try{
        if(!s||!path||!path[0]||bytes<1048576||bytes>64ULL*1073741824||bytes%1048576||bypass<0||bypass>1)throw Failure{2,0};
        check(s);auto storage=std::make_unique<BBStorage>(s,path,bytes,bypass!=0);
        // 独占创建和所有权先完成；后续失败只清理本次文件。 / Establish exclusive ownership before any fallible preparation.
        storage->file=std::make_unique<File>(storage->path,true,false);storage->owned=true;
        storage->pattern.resize(PoolBytes/8);
        for(size_t i=0;i<storage->pattern.size();++i){if(i%65536==0)check(s);storage->pattern[i]=mix(Seed+(i+1)*0x9e3779b97f4a7c15ULL);}
        for(uint64_t offset=0;offset<bytes;offset+=1048576){check(s);storage->file->transfer(true,reinterpret_cast<char*>(storage->pattern.data())+offset%PoolBytes,1048576,offset);storage->written+=1048576;}
        storage->file->sync();check(s);result->prepare_bytes=bytes;result->written_bytes_total=storage->written;result->measurement.wall_ns=clockNs()-start;result->error_phase=0;
        return storage.release();
    }catch(const Failure& e){result->measurement.status=e.status;result->error_number=e.error;}catch(...){result->measurement.status=3;}
    result->measurement.wall_ns=clockNs()-start;return nullptr;
}
extern "C" void bb_storage_destroy(BBStorage* storage){delete storage;}
extern "C" BBStorageResult bb_storage_run(BBStorage* storage,int32_t writeTest,int32_t randomAccess,int32_t block,int32_t depth,int32_t threads,int32_t warm,int32_t duration){
    BBStorageResult out{};auto& measurement=out.measurement;measurement.kind=writeTest;measurement.threads=threads;
    out.queue_depth=depth;out.block_bytes=block;out.random_access=randomAccess;const auto wall=clockNs();
    try{
        if(!storage||writeTest<0||writeTest>1||randomAccess<0||randomAccess>1||block<4096||block>4194304||(block&(block-1))||depth<1||depth>64||threads<1||threads>16||depth*threads>512||uint64_t(block)*depth*threads>256ULL*1048576||warm<0||warm>10000||duration<5||duration>30000||storage->bytes/uint64_t(block)/uint64_t(threads)<uint64_t(depth))throw Failure{2,0};
        measurement.working_set_bytes=storage->bytes;out.prepare_bytes=storage->bytes;check(storage->session);
        auto stage=[&](int milliseconds,bool scored){
            if(milliseconds==0)return;
            out.error_phase=scored?3:2;
            std::mutex mutex;std::condition_variable condition;bool go=false,verifyGo=false;int ready=0,measured=0;uint64_t start=0;
            std::atomic<bool> stop{false};std::vector<std::thread> workers;std::vector<WorkerResult> results(size_t(threads),WorkerResult{});
            std::vector<Failure> errors(size_t(threads),Failure{0,0});std::vector<int> hints(size_t(threads),0);
            try{for(int id=0;id<threads;++id)workers.emplace_back([&,id]{bool arrived=false,counted=false;try{
                File file(storage->path,false,storage->bypass);hints[size_t(id)]=file.noCache;
                std::vector<std::unique_ptr<Slot>> slots;for(int i=0;i<depth;++i)slots.push_back(std::make_unique<Slot>(size_t(block)));
                {std::unique_lock lock(mutex);++ready;arrived=true;condition.notify_all();condition.wait(lock,[&]{return go;});}
                auto& result=results[size_t(id)];result.startDelay=clockNs()-start;const auto deadline=start+uint64_t(milliseconds)*1000000;
                const uint64_t count=storage->bytes/uint64_t(block)/uint64_t(threads),base=count*uint64_t(id);uint64_t next=0,lastTick=start;
                Random rng{Seed+uint64_t(id)};int pending=0;uint64_t retryStart=0;bool started=false;
                auto account=[&]{const auto tick=clockNs();result.queueArea+=double(tick-lastTick)*pending;lastTick=tick;};
                try{
                    while(true){
                        check(storage->session);if(stop.load())throw Failure{5,0};
                        bool submitted=false,finished=false;
                        for(auto& slot:slots){
                            // 至少提交首批请求；短预热不能因调度迟到而被误判为文件错误。实际等待仍计入时间。
                            // Submit a first batch even after a delayed wakeup; retain the real delay in elapsed time instead of reporting a false file error.
                            if(!slot->active&&(!started||clockNs()<deadline)){
                                slot->completed=false;
                                slot->offset=(base+(randomAccess?rng.bounded(count):next))*uint64_t(block);next=(next+1)%count;
                                if(writeTest)storage->fill(slot->buffer.data(),slot->offset,size_t(block));
                                if(depth==1){const auto begin=clockNs();file.transfer(writeTest!=0,slot->buffer.data(),size_t(block),slot->offset);const auto end=clockNs();
                                    result.queueArea+=double(end-begin);result.maximum=1;++result.operations;result.ended=end;slot->completed=true;finished=true;}
                                else if(slot->submit(file,writeTest!=0)){account();++pending;result.maximum=std::max(result.maximum,pending);submitted=true;retryStart=0;}
                                else {result.limited=true;if(!retryStart)retryStart=clockNs();if(pending==0&&clockNs()-retryStart>1000000000ULL)throw Failure{5,EAGAIN};break;}
                            }
                        }
                        if(submitted||finished)started=true;
                        if(depth>1)for(auto& slot:slots)if(slot->active&&slot->finish(file,false)){account();--pending;++result.operations;result.ended=clockNs();finished=true;}
                        if(started&&clockNs()>=deadline&&pending==0)break;
                        if(!submitted&&!finished){
#if defined(_WIN32)
                            std::this_thread::sleep_for(std::chrono::milliseconds(1));
#else
                            std::vector<const aiocb*> list;for(auto& slot:slots)if(slot->active)list.push_back(&slot->op);
                            if(!list.empty()){timespec limit{0,10000000};aio_suspend(list.data(),int(list.size()),&limit);}else std::this_thread::yield();
#endif
                        }
                    }
                }catch(...){
                    // 即使取消/失败也必须取回所有请求，之后才释放缓冲与文件。 / Reap every submitted request before freeing its buffer or descriptor.
                    for(auto& slot:slots)if(slot->active)try{if(slot->finish(file,true)){++result.operations;result.ended=clockNs();}}catch(...){}
                    throw;
                }
                // 所有线程结束 I/O 后再校验，避免干扰仍在计时的线程。 / Validate only after every worker stops timed I/O.
                {std::unique_lock lock(mutex);++measured;counted=true;condition.notify_all();condition.wait(lock,[&]{return verifyGo;});}
                if(!writeTest)for(auto& slot:slots)if(slot->completed&&!storage->valid(slot->buffer.data(),slot->offset,size_t(block)))throw Failure{4,0};
            }catch(const Failure& e){errors[size_t(id)]=e;stop=true;}catch(...){errors[size_t(id)]={3,0};stop=true;}
                {std::lock_guard lock(mutex);if(!arrived)++ready;if(!counted)++measured;condition.notify_all();}
            });
            {std::unique_lock lock(mutex);condition.wait(lock,[&]{return ready==threads;});start=clockNs();go=true;condition.notify_all();
                condition.wait(lock,[&]{return measured==threads;});verifyGo=true;condition.notify_all();}
            }catch(...){stop=true;{std::lock_guard lock(mutex);go=true;verifyGo=true;condition.notify_all();}for(auto& worker:workers)worker.join();throw;}
            for(auto& worker:workers)worker.join();
            uint64_t operations=0,ended=start;double area=0;int maximum=0;
            for(const auto& r:results){operations+=r.operations;ended=std::max(ended,r.ended);area+=r.queueArea;maximum=std::max(maximum,r.maximum);}
            if(writeTest)storage->written+=operations*uint64_t(block);
            check(storage->session);for(const auto& error:errors)if(error.status)throw error;
            if(!operations||ended<=start)throw Failure{7,0};
            measurement.no_cache=std::all_of(hints.begin(),hints.end(),[](int value){return value!=0;});
            if(scored){measurement.trials[0]={ended-start,0,operations,operations*uint64_t(block),1};
                out.max_outstanding=maximum;out.mean_outstanding=area/double(ended-start)/threads;
                out.resource_limited=std::any_of(results.begin(),results.end(),[](const auto& r){return r.limited;});
                out.minimum_worker_operations=results.front().operations;
                for(const auto& r:results){out.start_delay_ns=std::max(out.start_delay_ns,r.startDelay);out.minimum_worker_operations=std::min(out.minimum_worker_operations,r.operations);}}
            else measurement.warmup_operations=operations;
            if(writeTest){out.error_phase=4;const auto flush=clockNs();storage->file->sync();if(scored)out.flush_ns=clockNs()-flush;}
        };
        stage(warm,false);stage(duration,true);
        // 独立校验跨文件样本，初始化和同步均不进入吞吐计时。 / Independently verify samples across the file outside throughput timing.
        out.error_phase=5;File verify(storage->path,false,storage->bypass);
        std::vector<uint64_t> buffer(4096/8);for(uint64_t i=0;i<16;++i){check(storage->session);const auto at=(storage->bytes/4096-1)*i/15*4096;
            verify.transfer(false,buffer.data(),4096,at);if(!storage->valid(buffer.data(),at,4096))throw Failure{4,0};}
        measurement.verified=1;measurement.trial_count=1;out.error_phase=0;
    }catch(const Failure& e){measurement.status=e.status;out.error_number=e.error;}catch(...){measurement.status=3;}
    if(storage)out.written_bytes_total=storage->written;
    measurement.wall_ns=clockNs()-wall;return out;
}
