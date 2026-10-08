#include "BenchCore.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <memory>
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
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
