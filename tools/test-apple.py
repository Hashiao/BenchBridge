"""macOS 上构建与实跑 iPhone/iPad 模拟器；记录真实运行时。 / Build and run iPhone/iPad simulators on macOS, recording actual runtimes."""
import argparse,json,os,platform,re,shutil,subprocess,sys,time,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.local/apple-ci'
OUT.mkdir(parents=True,exist_ok=True)
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--ci',action='store_true');args=parser.parse_args()
if platform.system()!='Darwin':raise SystemExit('Requires macOS/Xcode. Windows native validation: tools/test-apple-core.ps1')
def run(command,log=None):
    print('+ '+' '.join(map(str,command)),flush=True)
    if log:
        with (OUT/log).open('w') as stream:
            process=subprocess.Popen(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
            previous=0
            # 定期给出真实日志进度，限制单步挂起；完整输出仍保留。 / Report actual log progress periodically and bound hangs; retain full output.
            for _ in range(40):
                try:process.wait(timeout=30)
                except subprocess.TimeoutExpired:pass
                lines=(OUT/log).read_text(errors='replace').splitlines()
                if len(lines)>previous:print('\n'.join(lines[max(previous,len(lines)-8):]),flush=True)
                previous=len(lines)
                if process.poll() is not None:break
            else:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait()
                raise RuntimeError(f'{log} exceeded 20 minutes')
            r=process
        if r.returncode:
            lines=(OUT/log).read_text(errors='replace').splitlines();print('\n'.join(lines[-100:]),flush=True)
            raise RuntimeError(f'{command[0]} failed ({r.returncode}); see {log}')
        return ''
    return subprocess.check_output(command,cwd=ROOT,text=True,timeout=300).strip()
manifest={'host':platform.platform(),'status':'running','devices':[],'device_performance_verified':False}
def save(): (OUT/'verification.json').write_text(json.dumps(manifest,indent=2)+'\n')
save()
try:
    run([sys.executable,'tools/generate-apple-project.py'])
    if args.ci:
        candidates=list(Path('/Applications').glob('Xcode*.app'))
        versions=[(tuple(int(n) for n in re.findall(r'\d+',p.name)),p) for p in candidates if re.search(r'Xcode_\d',p.name)]
        # macOS 15 优先保留 iOS 18 的基线工具链。 / Prefer the iOS 18 baseline toolchain on macOS 15.
        major=int(platform.mac_ver()[0].split('.')[0])
        preferred=[pair for pair in versions if (pair[0][0]==16 if major==15 else pair[0][:2]==(27,0) if major>=27 else True) and 'beta' not in pair[1].name.lower()]
        if preferred:os.environ['DEVELOPER_DIR']=str(max(preferred)[1]/'Contents/Developer')
    manifest['xcode']=run(['xcodebuild','-version']);manifest['sdks']=run(['xcodebuild','-showsdks']);save()
    metal=subprocess.run(['xcrun','-sdk','iphoneos','metal','--version'],capture_output=True,text=True)
    if metal.returncode:run(['xcodebuild','-downloadComponent','MetalToolchain'],'metal-toolchain.log')
    native=OUT/'core-tests'
    run(['xcrun','clang++','-std=c++20','-O2','-fno-lto','-Wall','-Wextra','-Werror',
         'ios/Native/BenchCore.cpp','ios/Native/StorageCore.cpp','ios/Native/SharedKernels.cpp','ios/Native/SharedCompute.cpp','ios/Native/CoreTests.cpp','-o',str(native)],'native-build.log')
    run([str(native),str(OUT/'native-files')],'native-tests.log');manifest['native_tests']='passed';save()
    base=['xcodebuild','-project','ios/BenchBridge.xcodeproj','-scheme','BenchBridge','-configuration','Release','CODE_SIGNING_ALLOWED=NO']
    run(base+['-sdk','iphoneos','-destination','generic/platform=iOS','-derivedDataPath',str(OUT/'device-build'),'build'],'device-build.log')
    application=OUT/'device-build/Build/Products/Release-iphoneos/BenchBridge.app'
    import plistlib
    info=plistlib.loads((application/'Info.plist').read_bytes());assert info['CFBundleSupportedPlatforms']==['iPhoneOS'],info
    with zipfile.ZipFile(OUT/'BenchBridge-iOS-unsigned.ipa','w',compression=zipfile.ZIP_DEFLATED) as archive:
        for file in application.rglob('*'):
            if file.is_file():archive.write(file,Path('Payload/BenchBridge.app')/file.relative_to(application))
    manifest['unsigned_device_build']='passed';manifest['install_requires_resigning']=True;save()
    sdk=tuple(map(int,run(['xcrun','--sdk','iphonesimulator','--show-sdk-version']).split('.')[:2]))
    available=json.loads(run(['xcrun','simctl','list','devices','available','-j']))['devices']
    options=[(runtime,d) for runtime,devices in available.items() if 'iOS' in runtime and tuple(map(int,re.findall(r'\d+',runtime)))[:2]<=sdk for d in devices if d.get('isAvailable')]
    for family in ('iPhone','iPad'):
        choices=[item for item in options if family in item[1]['name']]
        if not choices:raise RuntimeError('No available '+family+' runtime')
        runtime,device=max(choices,key=lambda item:(tuple(map(int,re.findall(r'\d+',item[0]))),item[1]['name']))
        udid=device['udid'];entry={'family':family,'name':device['name'],'runtime':runtime,'udid':udid,'status':'running'};manifest['devices'].append(entry);save()
        if device.get('state')!='Booted':run(['xcrun','simctl','boot',udid])
        run(['xcrun','simctl','bootstatus',udid,'-b'])
        try:
            run(base+['-destination','id='+udid,'-derivedDataPath',str(OUT/'sim-build'),'-resultBundlePath',str(OUT/(family+'.xcresult')),
                      '-parallel-testing-enabled','NO','-test-timeouts-enabled','YES',
                      '-default-test-execution-time-allowance','180','-maximum-test-execution-time-allowance','300','test'],family+'-tests.log')
            entry['status']='passed'
            entry['skipped_tests']=re.findall(r"Test Case .*skipped.*",(OUT/(family+'-tests.log')).read_text(errors='replace'))
            run(['xcrun','simctl','io',udid,'screenshot',str(OUT/(family+'.png'))])
            # 保留测试记录、日志、IPA 与截图，不上传整个构建缓存。 / Keep results, logs, IPA and screenshots, not all build caches.
        except Exception:
            entry['status']='failed';raise
        finally:
            result=OUT/(family+'.xcresult')
            if result.exists():
                subprocess.run(['xcrun','xcresulttool','export','attachments','--path',str(result),'--output-path',str(OUT/(family+'-screenshots'))],check=False)
            subprocess.run(['xcrun','simctl','shutdown',udid],check=False);save()
    manifest['status']='passed';save()
except Exception as error:
    manifest['status']='failed';manifest['error']=str(error);save();raise
finally:
    for directory in ('device-build','sim-build','native-files'):
        path=(OUT/directory).resolve()
        if path.is_relative_to(OUT.resolve()) and path.exists():shutil.rmtree(path)
