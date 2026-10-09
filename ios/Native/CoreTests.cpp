#include "BenchCore.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <memory>
#include <vector>
#if defined(__APPLE__)
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
#if defined(__APPLE__)
// 只查询文件缓存驻留，不读映射页；对照缓存初始化与无缓存初始化。
// Query file-page residency without touching mapped pages; compare buffered and no-cache preparation.
uint64_t residentBytes(const std::filesystem::path& path,size_t bytes){
    const int fd=open(path.c_str(),O_RDONLY);require(fd>=0,"open residency probe");
    void* mapping=mmap(nullptr,bytes,PROT_READ,MAP_SHARED,fd,0);close(fd);require(mapping!=MAP_FAILED,"map residency probe");
    const size_t page=size_t(sysconf(_SC_PAGESIZE));std::vector<char> pages((bytes+page-1)/page);
    const int result=mincore(mapping,bytes,pages.data());munmap(mapping,bytes);require(result==0,"query residency probe");
    uint64_t resident=0;for(char value:pages)if(value&1)resident+=page;return resident;
}
#endif
int main(int argc,char** argv){try{
    require(argc==2,"provide an owned test directory");const auto root=std::filesystem::absolute(argv[1]);std::filesystem::create_directories(root);
    std::unique_ptr<BBSession,decltype(&bb_session_destroy)> s(bb_session_create(),bb_session_destroy);require(bool(s),"session");
    for(auto stride:{32,64,128,256})for(auto bytes:{4096ULL,65536ULL,1048832ULL})require(bb_validate_chain(bytes,stride,991),"full chain");
    require(!bb_validate_chain(4096,0,1)&&!bb_validate_chain(4000,64,1),"reject invalid chain");
    for(auto kind:{0,1,2})for(auto threads:{1,2}){
        const auto r=bb_memory(s.get(),kind,2*1048576,threads,5,15,0,991);
        require(r.status==0&&r.verified&&r.trial_count==1,"memory verification");
        const auto& t=r.trials[0];require(t.elapsed_ns>=15000000&&t.operations>0&&t.logical_bytes>0,"actual counts");
        require(t.logical_bytes==t.operations*uint64_t(kind==2?16:8),"copy counts reads and writes");
    }
    const auto latency=bb_memory(s.get(),5,1048576,1,5,15,0,991);
    require(latency.verified&&latency.warmup_operations>=1048576/64*2&&latency.trials[0].operations>0,"latency warmup and count");
    require(bb_memory(s.get(),5,4096,2,5,15,0,1).status==2,"latency is single-threaded");
    const auto point=bb_cache_point(s.get(),65536,64,1,37);require(point.verified&&point.trial_count==7,"repeated point");
    for(int i=0;i<7;++i)require(point.trials[i].elapsed_ns>0&&point.trials[i].operations>0,"raw point samples");
    const auto singlePoint=bb_cache_point_once(s.get(),65536,64,1,37);
    require(singlePoint.verified&&singlePoint.trial_count==1&&singlePoint.trials[0].accepted,"single-sample curve has exactly one valid measurement");
    for(int kind=3;kind<=7;++kind){auto r=bb_cpu_compute(s.get(),kind,5);require(r.verified&&r.trials[0].operations>0,"CPU scalar reference");
        uint32_t output[8]{};require(bb_compute_reference(kind,13,1024,output)&&bb_check_compute(kind,13,1024,output),"shared compute reference");output[0]^=0x40000000;require(!bb_check_compute(kind,13,1024,output),"reject corrupt compute");}
    for(auto kind:{10,11})require(bb_cpu_compute(s.get(),kind,5).verified,"fractal reference");
#if defined(__APPLE__)
    for(auto kind:{8,9})require(bb_cpu_compute(s.get(),kind,5).verified,"Apple crypto validation");
#endif
    for(auto write:{0,1})for(auto random:{0,1}){
        const auto path=root/("io-"+std::to_string(write)+std::to_string(random)+".bin");
        const auto r=bb_storage(s.get(),path.string().c_str(),write,random,1048576,4096,10);
        require(r.verified&&r.trials[0].logical_bytes==r.trials[0].operations*4096,"I/O byte accounting");require(!std::filesystem::exists(path),"owned file cleanup");
    }
    const auto original=root/"preexisting.txt";{std::ofstream file(original);file<<"preserve";}
    BBStorageResult preparation{};
    require(!bb_storage_create(s.get(),original.string().c_str(),1048576,0,&preparation)&&preparation.measurement.status==5,"queued storage preserves existing files");
    const auto queuedPath=root/"queued.bin";
    std::unique_ptr<BBStorage,decltype(&bb_storage_destroy)> storage(bb_storage_create(s.get(),queuedPath.string().c_str(),8*1048576,0,&preparation),bb_storage_destroy);
    require(bool(storage)&&preparation.prepare_bytes==8*1048576,"storage prepares the complete file once");
#if defined(__APPLE__)
    const auto uncachedPath=root/"uncached.bin";BBStorageResult coldPreparation{};
    std::unique_ptr<BBStorage,decltype(&bb_storage_destroy)> uncached(bb_storage_create(s.get(),uncachedPath.string().c_str(),8*1048576,1,&coldPreparation),bb_storage_destroy);
    require(bool(uncached)&&coldPreparation.preparation_no_cache&&coldPreparation.measurement.no_cache,"no-cache applies before the first initialization write");
    const auto cached=residentBytes(queuedPath,8*1048576),cold=residentBytes(uncachedPath,8*1048576);
    std::cout<<"Preparation cache residency: buffered="<<cached<<" no-cache="<<cold<<'\n';
    require(cached>=4*1048576&&cold<1048576,"no-cache preparation must not seed the first read with a resident file");
    for(auto queue:{8,1}){
        const auto read=bb_storage_run(uncached.get(),0,0,1048576,queue,1,0,100);
        require(read.measurement.verified&&read.preparation_no_cache&&read.measurement.no_cache,"first and later sequential reads keep the no-cache policy");
        require(read.submitted_operations==read.measurement.trials[0].operations&&read.completed_bytes==read.measurement.trials[0].logical_bytes,"Q8/Q1 count actual completed bytes once");
    }
#endif
    uint64_t previousWrites=preparation.written_bytes_total;
    for(auto queue:{1,8,32})for(auto write:{0,1}){
        const auto result=bb_storage_run(storage.get(),write,1,4096,queue,1,5,30);
        const auto& m=result.measurement;require(m.status==0&&m.verified&&m.trial_count==1,"asynchronous storage validation");
        require(m.trials[0].logical_bytes==m.trials[0].operations*4096,"queued byte accounting");
        require(result.submitted_operations==m.trials[0].operations&&result.completed_bytes==m.trials[0].logical_bytes,"submitted/completed request ledger");
        require(result.completion_wall_ns>=m.trials[0].elapsed_ns&&m.wall_ns>=result.completion_wall_ns,"independent completion timer contains the scored interval");
        require(result.buffer_alignment_bytes==65536,"aligned initialization and I/O buffers");
        std::cout<<"I/O Q"<<queue<<" write="<<write<<" achieved="<<result.max_outstanding<<" mean="<<result.mean_outstanding<<" limited="<<result.resource_limited<<'\n';
        require(result.max_outstanding>=(queue==1?1:2)&&result.max_outstanding<=queue&&
                (result.max_outstanding==queue||result.resource_limited)&&result.mean_outstanding>0&&result.mean_outstanding<=queue+0.01,"real outstanding I/O queue depth, with explicit kernel resource limits");
        require(result.written_bytes_total>=previousWrites&&result.prepare_bytes==8*1048576,"file reused and writes accumulated");
        if(write){require(result.flush_ns>0,"sync separately timed");require(result.written_bytes_total>=previousWrites+m.trials[0].logical_bytes,"writes counted including warmup");}
        else require(result.written_bytes_total==previousWrites,"reads do not initialize another file");
        previousWrites=result.written_bytes_total;
    }
    require(bb_storage_run(storage.get(),0,0,1048576,32,1,0,5).measurement.status==2,"reject file smaller than queue footprint");
    const auto multi=bb_storage_run(storage.get(),1,0,4096,8,2,0,30);require(multi.measurement.verified&&multi.measurement.threads==2,"disjoint multi-thread regions");
    const auto shortRun=bb_storage_run(storage.get(),0,1,4096,1,16,1,5);
    require(shortRun.measurement.verified&&shortRun.minimum_worker_operations>0,"short windows still execute work on every configured worker");
    BBStorageResult cancelledIO{};std::thread ioWorker([&]{cancelledIO=bb_storage_run(storage.get(),1,1,4096,32,1,0,5000);});
    std::this_thread::sleep_for(std::chrono::milliseconds(30));bb_session_cancel(s.get());ioWorker.join();
    require(cancelledIO.measurement.status==1&&!cancelledIO.measurement.verified,"cancel drains queued I/O without publishing a score");
    storage.reset();require(!std::filesystem::exists(queuedPath),"queued file ownership cleanup");s.reset(bb_session_create());
    require(bb_memory(s.get(),2,1048576,3,5,10,0,419).working_set_bytes==1048576,"unaligned thread counts preserve total RAM footprint");
    require(bb_storage(s.get(),original.string().c_str(),1,0,1048576,4096,5).status==5,"exclusive creation");
    std::ifstream originalFile(original);std::string contents;originalFile>>contents;require(contents=="preserve","never overwrite user file");originalFile.close();std::filesystem::remove(original);
    bb_session_cancel(s.get());require(bb_cache_point(s.get(),4096,64,0,1).status==1,"pre-cancelled point");
    require(bb_memory(s.get(),0,1048576,2,5,5,0,1).status==1,"cancelled worker barrier");
    s.reset(bb_session_create());BBResult cancelled{};const auto start=std::chrono::steady_clock::now();
    std::thread worker([&]{cancelled=bb_memory(s.get(),5,128ULL*1048576,1,1000,1000,0,1);});std::this_thread::sleep_for(std::chrono::milliseconds(10));bb_session_cancel(s.get());worker.join();
    require(cancelled.status==1&&std::chrono::steady_clock::now()-start<std::chrono::seconds(3),"responsive setup cancellation");
    std::cout<<"PASS: chain, byte accounting, repeated timing, CPU references, I/O cleanup, cancellation\n";
    return 0;
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<'\n';return 1;}}
