#include <jni.h>

#include <cstdint>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

class Reader {
public:
    explicit Reader(const std::vector<std::uint8_t>& bytes) : bytes_(bytes) {}

    std::size_t position() const { return position_; }
    std::size_t remaining() const { return bytes_.size() - position_; }

    std::uint8_t u1() {
        require(1);
        return bytes_[position_++];
    }

    std::uint16_t u2() {
        require(2);
        std::uint16_t value = static_cast<std::uint16_t>(bytes_[position_]) << 8U |
                              static_cast<std::uint16_t>(bytes_[position_ + 1]);
        position_ += 2;
        return value;
    }

    std::uint32_t u4() {
        require(4);
        std::uint32_t value = static_cast<std::uint32_t>(bytes_[position_]) << 24U |
                              static_cast<std::uint32_t>(bytes_[position_ + 1]) << 16U |
                              static_cast<std::uint32_t>(bytes_[position_ + 2]) << 8U |
                              static_cast<std::uint32_t>(bytes_[position_ + 3]);
        position_ += 4;
        return value;
    }

    std::vector<std::uint8_t> take(std::size_t length) {
        require(length);
        auto begin = bytes_.begin() + static_cast<std::ptrdiff_t>(position_);
        position_ += length;
        return {begin, begin + static_cast<std::ptrdiff_t>(length)};
    }

    void skip(std::size_t length) {
        require(length);
        position_ += length;
    }

private:
    void require(std::size_t length) const {
        if (length > remaining()) {
            throw std::runtime_error("truncated class file");
        }
    }

    const std::vector<std::uint8_t>& bytes_;
    std::size_t position_ = 0;
};

void writeU1(std::vector<std::uint8_t>& output, std::uint8_t value) {
    output.push_back(value);
}

void writeU2(std::vector<std::uint8_t>& output, std::uint16_t value) {
    output.push_back(static_cast<std::uint8_t>(value >> 8U));
    output.push_back(static_cast<std::uint8_t>(value));
}

void writeU4(std::vector<std::uint8_t>& output, std::uint32_t value) {
    output.push_back(static_cast<std::uint8_t>(value >> 24U));
    output.push_back(static_cast<std::uint8_t>(value >> 16U));
    output.push_back(static_cast<std::uint8_t>(value >> 8U));
    output.push_back(static_cast<std::uint8_t>(value));
}

void append(std::vector<std::uint8_t>& output, const std::vector<std::uint8_t>& bytes) {
    output.insert(output.end(), bytes.begin(), bytes.end());
}

struct ConstantPoolEntry {
    std::uint8_t tag = 0;
    std::vector<std::uint8_t> payload;
    std::uint16_t index1 = 0;
    std::uint16_t index2 = 0;
    std::string utf8;
};

struct Attribute {
    std::uint16_t nameIndex;
    std::vector<std::uint8_t> info;
};

struct Member {
    std::uint16_t accessFlags;
    std::uint16_t nameIndex;
    std::uint16_t descriptorIndex;
    std::vector<Attribute> attributes;
};

using ConstantPool = std::vector<ConstantPoolEntry>;
using RenameMap = std::unordered_map<std::string, std::uint16_t>;

std::string constantUtf8(const ConstantPool& pool, std::uint16_t index) {
    if (index == 0 || index >= pool.size() || pool[index].tag != 1) {
        return {};
    }
    return pool[index].utf8;
}

std::string className(const ConstantPool& pool, std::uint16_t index) {
    if (index == 0 || index >= pool.size() || pool[index].tag != 7) {
        return {};
    }
    return constantUtf8(pool, pool[index].index1);
}

std::string memberKey(const ConstantPool& pool, std::uint16_t nameIndex, std::uint16_t descriptorIndex) {
    return constantUtf8(pool, nameIndex) + "\x1f" + constantUtf8(pool, descriptorIndex);
}

ConstantPoolEntry readConstant(Reader& reader) {
    ConstantPoolEntry entry;
    entry.tag = reader.u1();
    switch (entry.tag) {
        case 1: {
            std::uint16_t length = reader.u2();
            auto bytes = reader.take(length);
            writeU2(entry.payload, length);
            append(entry.payload, bytes);
            entry.utf8.assign(bytes.begin(), bytes.end());
            break;
        }
        case 3:
        case 4:
            entry.payload = reader.take(4);
            break;
        case 5:
        case 6:
            entry.payload = reader.take(8);
            break;
        case 7:
        case 8:
        case 16:
        case 19:
        case 20:
            entry.index1 = reader.u2();
            writeU2(entry.payload, entry.index1);
            break;
        case 9:
        case 10:
        case 11:
        case 12:
        case 17:
        case 18:
            entry.index1 = reader.u2();
            entry.index2 = reader.u2();
            writeU2(entry.payload, entry.index1);
            writeU2(entry.payload, entry.index2);
            break;
        case 15:
            entry.payload.push_back(reader.u1());
            entry.index1 = reader.u2();
            writeU2(entry.payload, entry.index1);
            break;
        default:
            throw std::runtime_error("unsupported constant pool tag");
    }
    return entry;
}

void writeConstant(std::vector<std::uint8_t>& output, const ConstantPoolEntry& entry) {
    writeU1(output, entry.tag);
    if (entry.tag == 7 || entry.tag == 8 || entry.tag == 16 || entry.tag == 19 || entry.tag == 20 ||
        entry.tag == 9 || entry.tag == 10 || entry.tag == 11 || entry.tag == 12 || entry.tag == 17 ||
        entry.tag == 18) {
        writeU2(output, entry.index1);
        if (entry.tag == 9 || entry.tag == 10 || entry.tag == 11 || entry.tag == 12 || entry.tag == 17 ||
            entry.tag == 18) {
            writeU2(output, entry.index2);
        }
        return;
    }
    append(output, entry.payload);
}

Attribute readAttribute(Reader& reader) {
    Attribute attribute{reader.u2(), {}};
    attribute.info = reader.take(reader.u4());
    return attribute;
}

std::vector<Member> readMembers(Reader& reader) {
    std::uint16_t count = reader.u2();
    std::vector<Member> members;
    members.reserve(count);
    for (std::uint16_t i = 0; i < count; ++i) {
        Member member{reader.u2(), reader.u2(), reader.u2(), {}};
        std::uint16_t attributeCount = reader.u2();
        member.attributes.reserve(attributeCount);
        for (std::uint16_t j = 0; j < attributeCount; ++j) {
            member.attributes.push_back(readAttribute(reader));
        }
        members.push_back(std::move(member));
    }
    return members;
}

void writeAttribute(std::vector<std::uint8_t>& output, const Attribute& attribute) {
    writeU2(output, attribute.nameIndex);
    writeU4(output, static_cast<std::uint32_t>(attribute.info.size()));
    append(output, attribute.info);
}

std::string hashMemberName(const std::string& owner, const std::string& name, const std::string& descriptor) {
    std::uint64_t hash = 1469598103934665603ULL;
    const std::string key = owner + "\x1f" + name + "\x1f" + descriptor;
    for (unsigned char value : key) {
        hash ^= value;
        hash *= 1099511628211ULL;
    }
    static constexpr char hex[] = "0123456789abcdef";
    std::string result = "cl$";
    for (int shift = 60; shift >= 0; shift -= 4) {
        result.push_back(hex[(hash >> shift) & 0x0fU]);
    }
    return result;
}

Attribute transformCode(const Attribute& codeAttribute, const ConstantPool& pool) {
    Reader code(codeAttribute.info);
    std::vector<std::uint8_t> info;
    writeU2(info, code.u2());
    writeU2(info, code.u2());

    std::uint32_t codeLength = code.u4();
    writeU4(info, codeLength);
    append(info, code.take(codeLength));

    std::uint16_t exceptionCount = code.u2();
    writeU2(info, exceptionCount);
    append(info, code.take(static_cast<std::size_t>(exceptionCount) * 8U));

    std::uint16_t nestedCount = code.u2();
    std::vector<Attribute> kept;
    const std::unordered_set<std::string> debugAttributes{
            "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable"};
    for (std::uint16_t i = 0; i < nestedCount; ++i) {
        Attribute nested = readAttribute(code);
        if (debugAttributes.count(constantUtf8(pool, nested.nameIndex)) == 0) {
            kept.push_back(std::move(nested));
        }
    }
    if (code.remaining() != 0) {
        throw std::runtime_error("invalid Code attribute");
    }
    writeU2(info, static_cast<std::uint16_t>(kept.size()));
    for (const Attribute& attribute : kept) {
        writeAttribute(info, attribute);
    }
    return {codeAttribute.nameIndex, std::move(info)};
}

void writeMembers(std::vector<std::uint8_t>& output, const std::vector<Member>& members,
                  const ConstantPool& pool, const RenameMap& renameMap, bool methods) {
    writeU2(output, static_cast<std::uint16_t>(members.size()));
    for (const Member& member : members) {
        writeU2(output, member.accessFlags);
        const std::string key = memberKey(pool, member.nameIndex, member.descriptorIndex);
        auto renamed = renameMap.find(key);
        writeU2(output, renamed == renameMap.end() ? member.nameIndex : renamed->second);
        writeU2(output, member.descriptorIndex);
        writeU2(output, static_cast<std::uint16_t>(member.attributes.size()));
        for (const Attribute& attribute : member.attributes) {
            if (methods && constantUtf8(pool, attribute.nameIndex) == "Code") {
                writeAttribute(output, transformCode(attribute, pool));
            } else {
                writeAttribute(output, attribute);
            }
        }
    }
}

std::vector<std::uint8_t> transform(const std::vector<std::uint8_t>& input) {
    Reader reader(input);
    if (reader.u4() != 0xCAFEBABEU) {
        throw std::runtime_error("invalid class file magic");
    }
    std::uint16_t minorVersion = reader.u2();
    std::uint16_t majorVersion = reader.u2();
    std::uint16_t poolCount = reader.u2();
    ConstantPool pool(poolCount);
    for (std::uint16_t i = 1; i < poolCount; ++i) {
        pool[i] = readConstant(reader);
        if (pool[i].tag == 5 || pool[i].tag == 6) {
            if (++i >= poolCount) {
                throw std::runtime_error("invalid wide constant pool entry");
            }
        }
    }

    std::uint16_t accessFlags = reader.u2();
    std::uint16_t thisClass = reader.u2();
    std::uint16_t superClass = reader.u2();
    std::uint16_t interfaceCount = reader.u2();
    std::vector<std::uint16_t> interfaces;
    interfaces.reserve(interfaceCount);
    for (std::uint16_t i = 0; i < interfaceCount; ++i) {
        interfaces.push_back(reader.u2());
    }
    std::vector<Member> fields = readMembers(reader);
    std::vector<Member> methods = readMembers(reader);
    std::uint16_t classAttributeCount = reader.u2();
    std::vector<Attribute> classAttributes;
    classAttributes.reserve(classAttributeCount);
    for (std::uint16_t i = 0; i < classAttributeCount; ++i) {
        classAttributes.push_back(readAttribute(reader));
    }
    if (reader.remaining() != 0) {
        throw std::runtime_error("trailing class file data");
    }

    const std::string owner = className(pool, thisClass);
    RenameMap renameMap;
    std::unordered_set<std::string> renamedNames;
    const auto collectPrivate = [&](const std::vector<Member>& members) {
        for (const Member& member : members) {
            const std::string name = constantUtf8(pool, member.nameIndex);
            if ((member.accessFlags & 0x0002U) == 0 || name.empty() || name[0] == '<') {
                continue;
            }
            const std::string key = memberKey(pool, member.nameIndex, member.descriptorIndex);
            std::string renamed = hashMemberName(owner, name, constantUtf8(pool, member.descriptorIndex));
            while (!renamedNames.insert(renamed).second) {
                renamed.push_back('x');
            }
            ConstantPoolEntry utf8Entry;
            utf8Entry.tag = 1;
            utf8Entry.utf8 = renamed;
            writeU2(utf8Entry.payload, static_cast<std::uint16_t>(renamed.size()));
            utf8Entry.payload.insert(utf8Entry.payload.end(), renamed.begin(), renamed.end());
            pool.push_back(std::move(utf8Entry));
            if (pool.size() > 65535U) {
                throw std::runtime_error("constant pool overflow");
            }
            renameMap.emplace(key, static_cast<std::uint16_t>(pool.size() - 1));
        }
    };
    collectPrivate(fields);
    collectPrivate(methods);

    std::unordered_map<std::string, std::uint16_t> renamedNameTypes;
    for (std::size_t index = 1; index < poolCount; ++index) {
        if (pool[index].tag != 9 && pool[index].tag != 10 && pool[index].tag != 11) {
            continue;
        }
        const std::uint16_t ownerIndex = pool[index].index1;
        const std::uint16_t nameTypeIndex = pool[index].index2;
        if (className(pool, ownerIndex) != owner || nameTypeIndex == 0 || nameTypeIndex >= poolCount ||
            pool[nameTypeIndex].tag != 12) {
            continue;
        }
        const std::uint16_t descriptorIndex = pool[nameTypeIndex].index2;
        const std::string key = memberKey(pool, pool[nameTypeIndex].index1, descriptorIndex);
        auto renamed = renameMap.find(key);
        if (renamed == renameMap.end()) {
            continue;
        }
        const std::string nameTypeKey = std::to_string(renamed->second) + "\x1f" +
                                        std::to_string(descriptorIndex);
        auto existing = renamedNameTypes.find(nameTypeKey);
        if (existing == renamedNameTypes.end()) {
            ConstantPoolEntry replacement;
            replacement.tag = 12;
            replacement.index1 = renamed->second;
            replacement.index2 = descriptorIndex;
            pool.push_back(std::move(replacement));
            if (pool.size() > 65535U) {
                throw std::runtime_error("constant pool overflow");
            }
            std::uint16_t replacementIndex = static_cast<std::uint16_t>(pool.size() - 1);
            existing = renamedNameTypes.emplace(nameTypeKey, replacementIndex).first;
        }
        pool[index].index2 = existing->second;
    }

    std::vector<std::uint8_t> output;
    writeU4(output, 0xCAFEBABEU);
    writeU2(output, minorVersion);
    writeU2(output, majorVersion);
    writeU2(output, static_cast<std::uint16_t>(pool.size()));
    for (std::size_t index = 1; index < pool.size(); ++index) {
        if (pool[index].tag != 0) {
            writeConstant(output, pool[index]);
        }
    }

    writeU2(output, accessFlags);
    writeU2(output, thisClass);
    writeU2(output, superClass);
    writeU2(output, static_cast<std::uint16_t>(interfaces.size()));
    for (std::uint16_t interfaceIndex : interfaces) {
        writeU2(output, interfaceIndex);
    }
    writeMembers(output, fields, pool, renameMap, false);
    writeMembers(output, methods, pool, renameMap, true);

    std::vector<Attribute> keptClassAttributes;
    const std::unordered_set<std::string> classDebugAttributes{"SourceFile", "SourceDebugExtension"};
    for (const Attribute& attribute : classAttributes) {
        if (classDebugAttributes.count(constantUtf8(pool, attribute.nameIndex)) == 0) {
            keptClassAttributes.push_back(attribute);
        }
    }
    writeU2(output, static_cast<std::uint16_t>(keptClassAttributes.size()));
    for (const Attribute& attribute : keptClassAttributes) {
        writeAttribute(output, attribute);
    }
    return output;
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_cloudlicense_obfuscation_NativeClassTransformer_transformClass(
        JNIEnv* env, jobject, jbyteArray classBytes) {
    if (classBytes == nullptr) {
        throwIllegalArgument(env, "class bytes must not be null");
        return nullptr;
    }
    try {
        jsize length = env->GetArrayLength(classBytes);
        std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(classBytes, 0, length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        std::vector<std::uint8_t> output = transform(input);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result == nullptr) {
            return nullptr;
        }
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                reinterpret_cast<const jbyte*>(output.data()));
        return result;
    } catch (const std::exception& exception) {
        throwIllegalArgument(env, exception.what());
        return nullptr;
    }
}
