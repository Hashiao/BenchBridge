#include "latency_probe.h"
#include "ram_kernels.h"
#include "affinity_diagnostics.h"
#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <memory>
#include <numeric>
#include <sched.h>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {
std::uint64_t clock_ns(clockid_t id) {
    timespec t{};
    if (clock_gettime(id,&t)) throw std::runtime_error("PROBE_CLOCK_FAILED");
    return std::uint64_t(t.tv_sec)*1000000000+t.tv_nsec;
}
struct Random {
    std::uint64_t state;
    std::uint64_t next() {
        auto x=(state+=UINT64_C(0x9e3779b97f4a7c15));
        x=(x^(x>>30))*UINT64_C(0xbf58476d1ce4e5b9);
        x=(x^(x>>27))*UINT64_C(0x94d049bb133111eb);return x^(x>>31);
    }
    std::uint64_t bounded(std::uint64_t n) { const auto t=-n%n; std::uint64_t x; do{x=next();}while(x<t);return x%n; }
};
double median(std::vector<double> values) {
    std::sort(values.begin(),values.end());const auto n=values.size();
    return n%2?values[n/2]:(values[n/2-1]+values[n/2])/2;
}
long frequency(int cpu) {
    std::ifstream input("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpufreq/scaling_cur_freq");
    long value=0;input>>value;return std::max(0L,value);
}
struct Trial { std::uint64_t wall=0,cpu=0,hops=0;long before=0,after=0;bool accepted=false; };
struct Free { void operator()(std::uint32_t* p)const{std::free(p);} };
}

std::string bb_latency_point(std::atomic<bool>& cancelled,std::atomic<int>& phase,int cpu,
                            std::uint64_t bytes,int stride,std::uint64_t seed,bool single_sample,int diagnostic_fault) {
    if(cpu<0||cpu>=CPU_SETSIZE||bytes<4096||bytes>256ULL*1048576||bytes%256||stride<32||stride>256||(stride&(stride-1)))
        return "{\"status\":\"FAILED\",\"error\":\"PROBE_PARAMETERS\"}";
    std::string output;
    bbdiag::Round diagnostics;
    diagnostics.requested.push_back(cpu); diagnostics.workers.resize(1);
    std::thread worker([&]{
        auto& diagnostic=diagnostics.workers[0];
        try {
            auto check=[&]{if(cancelled.load())throw std::runtime_error("RUN_CANCELLED");};
            check();phase.store(1);
            const auto affinity_error=diagnostic.pin(cpu,diagnostic_fault);
            if(!affinity_error.empty())throw std::runtime_error(affinity_error);
            void* allocation=nullptr;
            if(posix_memalign(&allocation,16384,bytes))throw std::bad_alloc{};
            std::unique_ptr<std::uint32_t,Free> data(static_cast<std::uint32_t*>(allocation));
            const auto words=bytes/4,nodes=bytes/stride;
            const auto step=std::uint32_t(stride/4);
            Random random{seed};
            // 全缓冲区高熵填充，避免常量填充影响；链保存索引而非可直接识别的指针。
            // Fill the entire buffer with high-entropy data; store indices rather than recognizable pointers.
            for(std::size_t i=0;i<words;++i){if((i&8191)==0)check();data.get()[i]=static_cast<std::uint32_t>(random.next());}
            std::vector<std::uint32_t> order(nodes);std::iota(order.begin(),order.end(),0);
            for(std::size_t i=nodes-1;i>0;--i){if((i&8191)==0)check();std::swap(order[i],order[random.bounded(i+1)]);}
            for(std::size_t i=0;i<nodes;++i)data.get()[order[i]*step]=order[(i+1)%nodes]*step;
            auto cursor=order.front()*step;const auto origin=cursor;
            for(std::size_t i=0;i<nodes;++i){
                if((i&8191)==0)check();cursor=data.get()[cursor];
                if(cursor>=words||cursor%step||(cursor==origin&&i+1!=nodes))throw std::runtime_error("PROBE_CHAIN_INVALID");
            }
            if(cursor!=origin)throw std::runtime_error("PROBE_CHAIN_NOT_CLOSED");
            order.clear();order.shrink_to_fit();
            const auto page=sysconf(_SC_PAGESIZE);
            auto block=[&](int milliseconds){
                Trial t;t.before=frequency(cpu);const auto start=clock_ns(CLOCK_MONOTONIC_RAW),cpuStart=clock_ns(CLOCK_THREAD_CPUTIME_ID);
                const auto end=start+std::uint64_t(milliseconds)*1000000;
                do{check();cursor=bb_chase_index(data.get(),cursor,4096);t.hops+=4096;}while(clock_ns(CLOCK_MONOTONIC_RAW)<end);
                t.cpu=clock_ns(CLOCK_THREAD_CPUTIME_ID)-cpuStart;t.wall=clock_ns(CLOCK_MONOTONIC_RAW)-start;t.after=frequency(cpu);
                t.accepted=t.wall<=std::uint64_t(milliseconds)*1250000+5000000 && t.cpu>=t.wall*0.90 &&
                    (!t.before||!t.after||double(std::max(t.before,t.after))/std::min(t.before,t.after)<=1.10);
                return t;
            };
            phase.store(2);std::uint64_t warmed=0;std::vector<double> warmValues;
            const auto warmStart=clock_ns(CLOCK_MONOTONIC_RAW);
            bool warmStable=false;
            do {
                const auto t=block(20);warmed+=t.hops;
                if(single_sample){if(warmed>=nodes*2 && clock_ns(CLOCK_MONOTONIC_RAW)-warmStart>=40000000)break;else continue;}
                if(t.wall<35000000)warmValues.push_back(double(t.wall)/t.hops);
                if(warmValues.size()>=3){auto a=warmValues.end()-3;const auto range=std::minmax_element(a,warmValues.end());warmStable=(*range.second-*range.first)/median(std::vector<double>(a,warmValues.end()))<=0.08;}
                if(warmed>=nodes*2 && warmStable && clock_ns(CLOCK_MONOTONIC_RAW)-warmStart>=60000000)break;
            }while(clock_ns(CLOCK_MONOTONIC_RAW)-warmStart<500000000ULL || (warmed<nodes*2 && clock_ns(CLOCK_MONOTONIC_RAW)-warmStart<2500000000ULL));
            const auto warmElapsed=clock_ns(CLOCK_MONOTONIC_RAW)-warmStart;
            phase.store(3);std::vector<Trial> trials;std::vector<double> values;
            diagnostic.start_cpu=sched_getcpu();
            for(int i=0;i<(single_sample?1:9);++i){
                auto t=block(30);t.accepted=(single_sample?(t.wall>0&&t.hops>0):t.accepted)&&warmed>=nodes*2;trials.push_back(t);
                if(t.accepted)values.push_back(double(t.wall)/t.hops);
                if(values.size()>=5){const auto range=std::minmax_element(values.begin(),values.end());if((*range.second-*range.first)/median(values)<=0.10)break;}
            }
            phase.store(4);check();
            diagnostic.end_cpu=sched_getcpu();
            if(diagnostic.start_cpu!=cpu||diagnostic.end_cpu!=cpu) {
                diagnostic.reason="observed_cpu_migrated";throw std::runtime_error("PROBE_VERIFY_FAILED");
            }
            if(cursor>=words||cursor%step)throw std::runtime_error("PROBE_VERIFY_FAILED");
            std::ostringstream s;s<<std::setprecision(12)<<"{\"status\":\"COMPLETED\",\"verified\":true,\"cpu_id\":"<<cpu
                <<",\"working_set_bytes\":"<<bytes<<",\"node_stride_bytes\":"<<stride<<",\"node_count\":"<<nodes<<",\"page_size_bytes\":"<<page
                <<",\"kernel\":\"dependent-index32-v1\",\"pattern\":\"global-random-high-entropy\",\"seed\":"<<seed
                <<",\"chain_verified_nodes\":"<<nodes<<",\"warmup_operations\":"<<warmed<<",\"warmup_elapsed_ns\":"<<warmElapsed
                <<",\"sampling_mode\":\""<<(single_sample?"single-sample":"repeated")<<"\""
                <<",\"warmup_stable\":"<<(warmStable?"true":"false")<<",\"allocation_count\":1,\"trials\":[";
            for(std::size_t i=0;i<trials.size();++i){const auto& t=trials[i];if(i)s<<',';
                s<<"{\"elapsed_ns\":"<<t.wall<<",\"cpu_elapsed_ns\":"<<t.cpu<<",\"operations\":"<<t.hops
                    <<",\"frequency_before_khz\":"<<t.before<<",\"frequency_after_khz\":"<<t.after<<",\"accepted\":"<<(t.accepted?"true":"false")<<'}';}
            s<<"]}";output=s.str();
        }catch(const std::bad_alloc&){output="{\"status\":\"FAILED\",\"error\":\"RAM_ALLOC_FAILED\"}";}
        catch(const std::exception& e){output="{\"status\":\""+std::string(cancelled.load()?"INTERRUPTED":"FAILED")+"\",\"error\":\""+e.what()+"\"}";}
        phase.store(0);
    });
    worker.join();return bbdiag::attach(output,diagnostics.json());
}
