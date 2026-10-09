"""生成不依赖第三方工具的 Xcode 工程。 / Generate the Xcode project without third-party tooling."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parents[1]/'ios'
def uid(value):return hashlib.sha256(value.encode()).hexdigest()[:24].upper()
def q(value):return json.dumps(str(value))
objects=[]
def add(name,body):
    identity=uid(name);objects.append(f'{identity} = {{ {body} }};');return identity
def array(values):return '('+','.join(values)+',)' if values else '()'
def settings(values):return '{'+''.join(f'{key} = {array([q(x) for x in val]) if isinstance(val,list) else q(val)};' for key,val in values.items())+'}'
groups=[];products=[];targets=[];configs={};buildRefs={}
sources={
    'BenchBridge': sorted([str(p.relative_to(ROOT)).replace('\\','/') for p in (ROOT/'BenchBridge').glob('*.swift')])+
        ['Native/BenchCore.cpp','Native/StorageCore.cpp','Native/SharedKernels.cpp','Native/SharedCompute.cpp','BenchBridge/BenchKernels.metal'],
    'BenchBridgeTests':['BenchBridgeTests/BenchBridgeTests.swift'],
    'BenchBridgeUITests':['BenchBridgeUITests/BenchBridgeUITests.swift']
}
common={'IPHONEOS_DEPLOYMENT_TARGET':'18.0','SDKROOT':'iphoneos','TARGETED_DEVICE_FAMILY':'1,2',
    'SWIFT_VERSION':'5.0','CLANG_CXX_LANGUAGE_STANDARD':'c++20','CLANG_CXX_LIBRARY':'libc++',
    'CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','GCC_SYMBOLS_PRIVATE_EXTERN':'NO',
    'ENABLE_TESTABILITY':'YES','MTL_FAST_MATH':'NO','SWIFT_STRICT_CONCURRENCY':'targeted',
    'GCC_OPTIMIZATION_LEVEL':'3','SWIFT_OPTIMIZATION_LEVEL':'-O','DEBUG_INFORMATION_FORMAT':'dwarf-with-dsym',
    'CODE_SIGN_STYLE':'Automatic','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator','SUPPORTS_MACCATALYST':'NO'}
for target,files in sources.items():
    references=[];builds=[]
    for path in files:
        extension=Path(path).suffix
        filetype={'.swift':'sourcecode.swift','.cpp':'sourcecode.cpp.cpp','.metal':'sourcecode.metal'}[extension]
        ref=add('file:'+path,f'isa = PBXFileReference; lastKnownFileType = {q(filetype)}; path = {q(path)}; sourceTree = "<group>";')
        references.append(ref);builds.append(add('build:'+target+path,f'isa = PBXBuildFile; fileRef = {ref};'))
    resourceBuild=[]
    if target=='BenchBridge':
        for path,filetype in [('BenchBridge/Info.plist','text.plist.xml'),('BenchBridge/PrivacyInfo.xcprivacy','text.xml'),('BenchBridge/Assets.xcassets','folder.assetcatalog')]:
            ref=add('file:'+path,f'isa = PBXFileReference; lastKnownFileType = {q(filetype)}; path = {q(path)}; sourceTree = "<group>";');references.append(ref)
            if path.endswith(('xcprivacy','xcassets')):resourceBuild.append(add('build:'+path,f'isa = PBXBuildFile; fileRef = {ref};'))
    groups.append(add('group:'+target,f'isa = PBXGroup; name = {q(target)}; children = {array(references)}; sourceTree = "<group>";'))
    product=add('product:'+target,f'isa = PBXFileReference; explicitFileType = {q("wrapper.application" if target=="BenchBridge" else "wrapper.cfbundle")}; includeInIndex = 0; path = {q(target+(".app" if target=="BenchBridge" else ".xctest"))}; sourceTree = BUILT_PRODUCTS_DIR;');products.append(product)
    sourcePhase=add('sources:'+target,f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {array(builds)}; runOnlyForDeploymentPostprocessing = 0;')
    frameworkPhase=add('frameworks:'+target,'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
    resourcePhase=add('resources:'+target,f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {array(resourceBuild)}; runOnlyForDeploymentPostprocessing = 0;')
    buildSettings=common|{'PRODUCT_NAME':target,'PRODUCT_BUNDLE_IDENTIFIER':'io.benchbridge.ios'+('' if target=='BenchBridge' else '.'+target.lower())}
    if target=='BenchBridge':
        buildSettings|={'INFOPLIST_FILE':'BenchBridge/Info.plist','ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','SWIFT_OBJC_BRIDGING_HEADER':'BenchBridge/BenchBridge-Bridging-Header.h',
            'HEADER_SEARCH_PATHS':['$(inherited)','$(SRCROOT)/Native'],'OTHER_CPLUSPLUSFLAGS':['$(inherited)','-fno-lto'],
            'LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks']}
    else:
        buildSettings|={'GENERATE_INFOPLIST_FILE':'YES','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks','@loader_path/Frameworks']}
        if target=='BenchBridgeTests':buildSettings|={'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/BenchBridge.app/BenchBridge','BUNDLE_LOADER':'$(TEST_HOST)',
            'SWIFT_OBJC_BRIDGING_HEADER':'BenchBridge/BenchBridge-Bridging-Header.h','HEADER_SEARCH_PATHS':['$(inherited)','$(SRCROOT)/Native']}
        else:buildSettings['TEST_TARGET_NAME']='BenchBridge'
    configIds=[]
    for configuration in ('Debug','Release'):
        values=buildSettings|({'SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG'} if configuration=='Debug' else {})
        configIds.append(add('config:'+target+configuration,f'isa = XCBuildConfiguration; name = {configuration}; buildSettings = {settings(values)};'))
    config=add('configlist:'+target,f'isa = XCConfigurationList; buildConfigurations = {array(configIds)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
    dependencies=[]
    if target!='BenchBridge':
        proxy=add('proxy:'+target,f'isa = PBXContainerItemProxy; containerPortal = {uid("project")}; proxyType = 1; remoteGlobalIDString = {uid("target:BenchBridge")}; remoteInfo = BenchBridge;')
        dependencies.append(add('dependency:'+target,f'isa = PBXTargetDependency; target = {uid("target:BenchBridge")}; targetProxy = {proxy};'))
    productType='application' if target=='BenchBridge' else 'bundle.unit-test' if target=='BenchBridgeTests' else 'bundle.ui-testing'
    targets.append(add('target:'+target,f'isa = PBXNativeTarget; buildConfigurationList = {config}; buildPhases = {array([sourcePhase,frameworkPhase,resourcePhase])}; buildRules = (); dependencies = {array(dependencies)}; name = {q(target)}; productName = {q(target)}; productReference = {product}; productType = {q("com.apple.product-type."+productType)};'))
productGroup=add('products',f'isa = PBXGroup; name = Products; children = {array(products)}; sourceTree = "<group>";')
mainGroup=add('main',f'isa = PBXGroup; children = {array(groups+[productGroup])}; sourceTree = "<group>";')
projectConfigs=[add('projectconfig:'+c,f'isa = XCBuildConfiguration; name = {c}; buildSettings = {{}};') for c in ('Debug','Release')]
projectList=add('projectconfigs',f'isa = XCConfigurationList; buildConfigurations = {array(projectConfigs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
project=add('project',f'isa = PBXProject; attributes = {{ LastUpgradeCheck = 1600; }}; buildConfigurationList = {projectList}; compatibilityVersion = "Xcode 14.0"; developmentRegion = zh-Hans; hasScannedForEncodings = 0; knownRegions = (en,Base,"zh-Hans"); mainGroup = {mainGroup}; productRefGroup = {productGroup}; projectDirPath = ""; projectRoot = ""; targets = {array(targets)};')
destination=ROOT/'BenchBridge.xcodeproj';destination.mkdir(exist_ok=True)
(destination/'project.pbxproj').write_text('// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n'+'\n'.join(objects)+f'\n}}; rootObject = {project};}}\n',encoding='utf-8')
scheme=destination/'xcshareddata/xcschemes';scheme.mkdir(parents=True,exist_ok=True)
def buildable(target):return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid("target:"+target)}" BuildableName="{target}{".app" if target=="BenchBridge" else ".xctest"}" BlueprintName="{target}" ReferencedContainer="container:BenchBridge.xcodeproj"/>'
(scheme/'BenchBridge.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{buildable('BenchBridge')}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Release" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>
<TestableReference skipped="NO" parallelizable="NO">{buildable('BenchBridgeTests')}</TestableReference><TestableReference skipped="NO" parallelizable="NO">{buildable('BenchBridgeUITests')}</TestableReference>
</Testables></TestAction>
<LaunchAction buildConfiguration="Release" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{buildable('BenchBridge')}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{buildable('BenchBridge')}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''',encoding='utf-8')
print(destination)
