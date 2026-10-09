import Foundation

// 设备标识精确匹配；资料库不决定绑核或缓存容量。 / Exact identities; catalog data never implies affinity or measured caches.
enum AppleCatalog {
    static func lookup(_ machine: String) -> AppleModelReference? {
        switch machine {
        case "iPhone11,2":
            return AppleModelReference(model: "iPhone Xs", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP779")
        case "iPhone11,4", "iPhone11,6":
            return AppleModelReference(model: "iPhone Xs Max", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP780")
        case "iPhone11,8":
            return AppleModelReference(model: "iPhone Xʀ", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP781")
        case "iPhone12,1":
            return AppleModelReference(model: "iPhone 11", soc: "A13 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP804")
        case "iPhone12,3":
            return AppleModelReference(model: "iPhone 11 Pro", soc: "A13 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP805")
        case "iPhone12,5":
            return AppleModelReference(model: "iPhone 11 Pro Max", soc: "A13 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP806")
        case "iPhone12,8":
            return AppleModelReference(model: "iPhone SE (2nd generation)", soc: "A13 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP820")
        case "iPhone13,2":
            return AppleModelReference(model: "iPhone 12", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP830")
        case "iPhone13,1":
            return AppleModelReference(model: "iPhone 12 mini", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP829")
        case "iPhone13,3":
            return AppleModelReference(model: "iPhone 12 Pro", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP831")
        case "iPhone13,4":
            return AppleModelReference(model: "iPhone 12 Pro Max", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP832")
        case "iPhone14,5":
            return AppleModelReference(model: "iPhone 13", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP851")
        case "iPhone14,4":
            return AppleModelReference(model: "iPhone 13 mini", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP847")
        case "iPhone14,2":
            return AppleModelReference(model: "iPhone 13 Pro", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP852")
        case "iPhone14,3":
            return AppleModelReference(model: "iPhone 13 Pro Max", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP848")
        case "iPhone14,6":
            return AppleModelReference(model: "iPhone SE (3rd generation)", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP867")
        case "iPhone14,7":
            return AppleModelReference(model: "iPhone 14", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP873")
        case "iPhone14,8":
            return AppleModelReference(model: "iPhone 14 Plus", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP874")
        case "iPhone15,2":
            return AppleModelReference(model: "iPhone 14 Pro", soc: "A16 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP875")
        case "iPhone15,3":
            return AppleModelReference(model: "iPhone 14 Pro Max", soc: "A16 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP876")
        case "iPhone15,4":
            return AppleModelReference(model: "iPhone 15", soc: "A16 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP901")
        case "iPhone15,5":
            return AppleModelReference(model: "iPhone 15 Plus", soc: "A16 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP902")
        case "iPhone16,1":
            return AppleModelReference(model: "iPhone 15 Pro", soc: "A17 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP903")
        case "iPhone16,2":
            return AppleModelReference(model: "iPhone 15 Pro Max", soc: "A17 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP904")
        case "iPhone17,3":
            return AppleModelReference(model: "iPhone 16", soc: "A18", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/121029")
        case "iPhone17,4":
            return AppleModelReference(model: "iPhone 16 Plus", soc: "A18", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/121030")
        case "iPhone17,1":
            return AppleModelReference(model: "iPhone 16 Pro", soc: "A18 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/121031")
        case "iPhone17,2":
            return AppleModelReference(model: "iPhone 16 Pro Max", soc: "A18 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/121032")
        case "iPhone17,5":
            return AppleModelReference(model: "iPhone 16e", soc: "A18", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/122208")
        case "iPhone18,3":
            return AppleModelReference(model: "iPhone 17", soc: "A19", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/125089")
        case "iPhone18,1":
            return AppleModelReference(model: "iPhone 17 Pro", soc: "A19 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/125090")
        case "iPhone18,2":
            return AppleModelReference(model: "iPhone 17 Pro Max", soc: "A19 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/125091")
        case "iPhone18,4":
            return AppleModelReference(model: "iPhone Air", soc: "A19 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/125092")
        case "iPhone18,5":
            return AppleModelReference(model: "iPhone 17e", soc: "A19", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/126470")
        case "iPhone19,2":
            return AppleModelReference(model: "iPhone 18 Pro", soc: "A20 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/en-us/148590")
        case "iPhone19,3", "iPhone19,7":
            return AppleModelReference(model: "iPhone 18 Pro Max", soc: "A20 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/en-us/148591")
        case "iPad11,3", "iPad11,4":
            return AppleModelReference(model: "iPad Air (3rd generation)", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP787")
        case "iPad11,6", "iPad11,7":
            return AppleModelReference(model: "iPad (8th generation)", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP822")
        case "iPad12,1", "iPad12,2":
            return AppleModelReference(model: "iPad (9th generation)", soc: "A13 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP849")
        case "iPad13,18", "iPad13,19":
            return AppleModelReference(model: "iPad (10th generation)", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP884")
        case "iPad15,7", "iPad15,8":
            return AppleModelReference(model: "iPad (A16)", soc: "A16 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/122240")
        case "iPad13,1", "iPad13,2":
            return AppleModelReference(model: "iPad Air (4th generation)", soc: "A14 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP828")
        case "iPad13,16", "iPad13,17":
            return AppleModelReference(model: "iPad Air (5th generation)", soc: "M1", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/kb/SP866")
        case "iPad14,8", "iPad14,9":
            return AppleModelReference(model: "iPad Air (11-inch) (M2)", soc: "M2", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/119894")
        case "iPad14,10", "iPad14,11":
            return AppleModelReference(model: "iPad Air (13-inch) (M2)", soc: "M2", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/119893")
        case "iPad15,3", "iPad15,4":
            return AppleModelReference(model: "iPad Air (11-inch) (M3)", soc: "M3", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/122241")
        case "iPad15,5", "iPad15,6":
            return AppleModelReference(model: "iPad Air (13-inch) (M3)", soc: "M3", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/122242")
        case "iPad16,8", "iPad16,9":
            return AppleModelReference(model: "iPad Air (11-inch) (M4)", soc: "M4", performanceCores: [3], efficiencyCores: [5], source: "https://support.apple.com/126471")
        case "iPad16,10", "iPad16,11":
            return AppleModelReference(model: "iPad Air (13-inch) (M4)", soc: "M4", performanceCores: [3], efficiencyCores: [5], source: "https://support.apple.com/126472")
        case "iPad11,1", "iPad11,2":
            return AppleModelReference(model: "iPad mini (5th generation)", soc: "A12 Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP788")
        case "iPad14,1", "iPad14,2":
            return AppleModelReference(model: "iPad mini (6th generation)", soc: "A15 Bionic", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/kb/SP850")
        case "iPad16,1", "iPad16,2":
            return AppleModelReference(model: "iPad mini (A17 Pro)", soc: "A17 Pro", performanceCores: [2], efficiencyCores: [4], source: "https://support.apple.com/en-us/121456")
        case "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4":
            return AppleModelReference(model: "iPad Pro (11-inch)", soc: "A12X Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP784")
        case "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8":
            return AppleModelReference(model: "iPad Pro (12.9-inch) (3rd generation)", soc: "A12X Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP785")
        case "iPad8,9", "iPad8,10":
            return AppleModelReference(model: "iPad Pro (11-inch) (2nd generation)", soc: "A12Z Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP814")
        case "iPad8,11", "iPad8,12":
            return AppleModelReference(model: "iPad Pro (12.9-inch) (4th generation)", soc: "A12Z Bionic", performanceCores: [], efficiencyCores: [], source: "https://support.apple.com/kb/SP815")
        case "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7":
            return AppleModelReference(model: "iPad Pro (11-inch) (3rd generation)", soc: "M1", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/kb/SP843")
        case "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11":
            return AppleModelReference(model: "iPad Pro (12.9-inch) (5th generation)", soc: "M1", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/kb/SP844")
        case "iPad14,3", "iPad14,4":
            return AppleModelReference(model: "iPad Pro (11-inch) (4th generation)", soc: "M2", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/kb/SP882")
        case "iPad14,5", "iPad14,6":
            return AppleModelReference(model: "iPad Pro (12.9-inch) (6th generation)", soc: "M2", performanceCores: [4], efficiencyCores: [4], source: "https://support.apple.com/kb/SP883")
        case "iPad16,3", "iPad16,4":
            return AppleModelReference(model: "iPad Pro (11-inch) (M4)", soc: "M4", performanceCores: [3, 4], efficiencyCores: [6], source: "https://support.apple.com/en-us/119892")
        case "iPad16,5", "iPad16,6":
            return AppleModelReference(model: "iPad Pro (13-inch) (M4)", soc: "M4", performanceCores: [3, 4], efficiencyCores: [6], source: "https://support.apple.com/en-us/119891")
        case "iPad17,1", "iPad17,2":
            return AppleModelReference(model: "iPad Pro (11-inch) (M5)", soc: "M5", performanceCores: [3, 4], efficiencyCores: [6], source: "https://support.apple.com/en-us/125406")
        case "iPad17,3", "iPad17,4":
            return AppleModelReference(model: "iPad Pro (13-inch) (M5)", soc: "M5", performanceCores: [3, 4], efficiencyCores: [6], source: "https://support.apple.com/en-us/125407")
        default: return nil
        }
    }
}
