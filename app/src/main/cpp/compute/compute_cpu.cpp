#include "compute_cpu_kernels.h"
#include <algorithm>
#include <barrier>
#include <condition_variable>
#include <mutex>
#include <sched.h>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace bbcompute {
std::string cpu_capabilities() {
    cpu_set_t mask;CPU_ZERO(&mask);const bool known=sched_getaffinity(0,sizeof(mask),&mask)==0;
    std::ostringstream s;s << "{\"cpu_ids\":[";bool first=true;
    if(known)for(int i=0;i<CPU_SETSIZE;++i)if(CPU_ISSET(i,&mask)){if(!first)s<<',';first=false;s<<i;}
    s << "],\"aes_accelerated\":" << (aes_accelerated()?"true":"false") << ",\"sha1_accelerated\":" << (sha_accelerated()?"true":"false")
      << ",\"abi\":" << quote(
#if defined(__aarch64__)
        "arm64-v8a"
#else
        "x86_64"
#endif
        ) << '}';return s.str();
}
std::string cpu_round(int kind,const std::vector<int>& cpus,int warmup_ms,int duration_ms,int width,int height,int memory_mib,std::atomic<bool>& cancelled) {
    if(kind<Fp32||kind>Mandel||cpus.empty()||cpus.size()>16||warmup_ms<0||warmup_ms>3000||duration_ms<50||duration_ms>10000||width<64||width>1024||height<64||height>1024||memory_mib<4||memory_mib>256)
        throw std::invalid_argument("COMPUTE_PARAMETERS");
    cpu_set_t allowed;CPU_ZERO(&allowed);
    if(sched_getaffinity(0,sizeof(allowed),&allowed)!=0)throw std::runtime_error("AFFINITY_QUERY_FAILED");
    for(std::size_t i=0;i<cpus.size();++i)if(cpus[i]<0||cpus[i]>=CPU_SETSIZE||!CPU_ISSET(cpus[i],&allowed)||std::find(cpus.begin(),cpus.begin()+i,cpus[i])!=cpus.begin()+i)
        throw std::invalid_argument("CPU_NOT_ALLOWED");
    if(!crypto_self_test())throw std::runtime_error("CRYPTO_SELF_TEST_FAILED");
    const bool crypto=kind==Aes256||kind==Sha1,hardwareAes=aes_accelerated(),hardwareSha=sha_accelerated();
    const std::size_t workingBytes=std::size_t(memory_mib)*1048576;
    const std::size_t inputBytes=crypto?workingBytes/(kind==Aes256?2:1):0;
    const std::size_t messages=inputBytes/crypto_message_bytes;
    std::vector<std::uint8_t> input(inputBytes),encrypted(kind==Aes256?inputBytes:0);
    std::vector<std::uint32_t> digests(kind==Sha1?messages*5:0);
    for(std::size_t offset=0;offset<inputBytes;offset+=crypto_message_bytes){
        if(cancelled.load())throw std::runtime_error("RUN_CANCELLED");
        fill_data(input.data()+offset,crypto_message_bytes,offset);
    }
    struct Worker{Words output{};std::vector<std::uint32_t> pixels;std::uint64_t jobs=0,warmJobs=0,end=0;std::size_t firstMessage=0,messageCount=0;std::uint32_t seed=0;int startCpu=-1,endCpu=-1;std::string error;};
    std::vector<Worker> workers(cpus.size());std::vector<std::thread> pool;
    std::barrier barrier(cpus.size()+1);std::atomic<bool> failed{false};
    std::mutex mutex;std::condition_variable gate;bool open=false,abort=false;
    std::uint64_t deadline=0,start=0;
    const std::uint32_t iterations=kind>=Julia?128:crypto?1:4096;
    auto work=[&](Worker& w,int cpu,bool measured){
        while(!cancelled.load()&&now_ns()<deadline){
            const auto job=measured?w.jobs:w.warmJobs;
            w.seed=0x1234u+static_cast<std::uint32_t>(job);
            if(crypto){
                const auto message=w.firstMessage+job%w.messageCount;
                const auto offset=message*crypto_message_bytes;
                if(kind==Aes256)aes_buffer(input.data()+offset,encrypted.data()+offset,crypto_message_bytes,hardwareAes);
                else sha_message(input.data()+offset,crypto_message_bytes,digests.data()+message*5,hardwareSha);
            }else if(kind>=Julia)cpu_frame(kind,width,height,w.pixels.data(),cancelled);
            else cpu_kernel(kind,cpu,iterations,w.seed,w.output);
            if(measured&&!cancelled.load())++w.jobs;
            if(!measured&&!cancelled.load())++w.warmJobs;
        }
    };
    try {
        for(std::size_t index=0;index<cpus.size();++index)pool.emplace_back([&,index]{
            {std::unique_lock guard(mutex);gate.wait(guard,[&]{return open;});if(abort)return;}
            auto& w=workers[index];
            try {
                cpu_set_t mask;CPU_ZERO(&mask);CPU_SET(cpus[index],&mask);
                if(sched_setaffinity(0,sizeof(mask),&mask)!=0)throw std::runtime_error("AFFINITY_FAILED");
                CPU_ZERO(&mask);
                if(sched_getaffinity(0,sizeof(mask),&mask)!=0||CPU_COUNT(&mask)!=1||!CPU_ISSET(cpus[index],&mask))throw std::runtime_error("AFFINITY_VERIFY_FAILED");
                if(kind>=Julia)w.pixels.resize(width*height);
                if(crypto){w.firstMessage=messages*index/cpus.size();w.messageCount=messages*(index+1)/cpus.size()-w.firstMessage;if(!w.messageCount)throw std::runtime_error("CRYPTO_PARTITION_EMPTY");}
            }catch(const std::exception& e){failed=true;w.error=e.what();}
            barrier.arrive_and_wait();if(failed)return;
            barrier.arrive_and_wait();work(w,cpus[index],false);barrier.arrive_and_wait();
            barrier.arrive_and_wait();w.startCpu=sched_getcpu();work(w,cpus[index],true);w.end=now_ns();w.endCpu=sched_getcpu();
        });
    }catch(...){ {std::lock_guard guard(mutex);abort=true;open=true;}gate.notify_all();for(auto& t:pool)t.join();throw; }
    {std::lock_guard guard(mutex);open=true;}gate.notify_all();
    barrier.arrive_and_wait();
    if(failed){for(auto& t:pool)t.join();throw std::runtime_error("CPU_PREPARE_FAILED");}
    deadline=now_ns()+std::uint64_t(warmup_ms)*1000000;barrier.arrive_and_wait();barrier.arrive_and_wait();
    std::fill(encrypted.begin(),encrypted.end(),0xcd);std::fill(digests.begin(),digests.end(),0xcdcdcdcdu);
    start=now_ns();deadline=start+std::uint64_t(duration_ms)*1000000;barrier.arrive_and_wait();
    for(auto& t:pool)t.join();
    Result r;r.kind=kind;r.backend="native-cpu-v2";r.interrupted=cancelled.load();r.verified=!r.interrupted;
    std::uint64_t end=start,total=0;
    for(std::size_t i=0;i<workers.size();++i){
        auto& w=workers[i];end=std::max(end,w.end);total+=w.jobs;
        r.verified &= w.jobs>0 && w.startCpu==cpus[i] && w.endCpu==cpus[i];
        if(r.verified){
            if(crypto){
                const auto touched=std::min<std::uint64_t>(w.messageCount,w.jobs);
                for(std::size_t n=0;n<std::min<std::uint64_t>(touched,32);++n){
                    if(cancelled.load())break;
                    const auto message=w.firstMessage+(n==0?0:n==1?touched-1:pattern(n,73)%touched);
                    const auto offset=message*crypto_message_bytes;
                    if(kind==Aes256){
                        for(std::size_t block:{std::size_t(0),crypto_message_bytes/2,crypto_message_bytes-16}){
                            std::uint8_t expected[16];aes_block(input.data()+offset+block,expected);
                            r.verified &= std::equal(expected,expected+16,encrypted.data()+offset+block);
                        }
                    }else{
                        std::uint32_t expected[5];sha_message(input.data()+offset,crypto_message_bytes,expected,false);
                        r.verified &= std::equal(expected,expected+5,digests.data()+message*5);
                    }
                }
            }else if(kind>=Julia){for(unsigned k=0;k<64;++k){auto p=pattern(k,7)%(width*height);r.verified &= w.pixels[p]==fractal_pixel(kind,p,width,height,128);}}
            else r.verified &= equal_output(kind,reference(kind,cpus[i],iterations,w.seed),w.output);
        }
    }
    r.interrupted=cancelled.load();r.verified &= !r.interrupted;
    r.elapsed=r.wall=end-start;r.work=total*(crypto?crypto_message_bytes:kind>=Julia?std::uint64_t(width)*height:units_per_item(kind,iterations));
    std::ostringstream extra;extra << ",\"threads\":" << cpus.size() << ",\"iterations_per_batch\":" << iterations << ",\"batches\":" << total
        << ",\"width\":" << width << ",\"height\":" << height << ",\"fractal_iterations\":128,\"warmup_ms\":" << warmup_ms << ",\"requested_duration_ms\":" << duration_ms
        << ",\"protocol\":\"gpgpu-v2\",\"timer_scope\":\"measurement-window\",\"working_set_bytes\":"<<(crypto?workingBytes:0)
        << ",\"input_bytes\":"<<inputBytes<<",\"output_bytes\":"<<(encrypted.size()+digests.size()*4)
        << ",\"message_bytes\":"<<(crypto?crypto_message_bytes:0)<<",\"messages_processed\":"<<(crypto?total:0)
        << ",\"frames\":"<<(kind>=Julia?total:0)<<",\"input_prepared_before_timing\":true"
        << ",\"aes_accelerated\":" << (hardwareAes?"true":"false") << ",\"sha1_accelerated\":" << (hardwareSha?"true":"false") << ",\"cpu_ids\":[";
    for(std::size_t i=0;i<cpus.size();++i){if(i)extra<<',';extra<<cpus[i];}extra<<"],\"observed_cpus\":[";
    for(std::size_t i=0;i<workers.size();++i){if(i)extra<<',';extra<<workers[i].endCpu;}extra<<']';r.extra=extra.str();return r.json();
}
}
