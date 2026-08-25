#include <jni.h>

#include <cstdint>
#include <stdexcept>
#include <string>
#include <unordered_set>
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

struct Attribute {
    std::uint16_t nameIndex;
    std::vector<std::uint8_t> info;
};

Attribute readAttribute(Reader& reader) {
    Attribute attribute{reader.u2(), {}};
    attribute.info = reader.take(reader.u4());
    return attribute;
}

void writeAttribute(std::vector<std::uint8_t>& output, const Attribute& attribute) {
    writeU2(output, attribute.nameIndex);
    writeU4(output, static_cast<std::uint32_t>(attribute.info.size()));
    append(output, attribute.info);
}

std::string constantName(const std::vector<std::string>& utf8, std::uint16_t index) {
    return index < utf8.size() ? utf8[index] : std::string();
}

Attribute transformCode(const Attribute& codeAttribute, const std::vector<std::string>& utf8) {
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
        if (debugAttributes.count(constantName(utf8, nested.nameIndex)) == 0) {
            kept.push_back(std::move(nested));
        }
    }
    if (code.remaining() != 0) {
        throw std::runtime_error("invalid Code attribute");
    }
    writeU2(info, static_cast<std::uint16_t>(kept.size()));
    for (const Attribute& nested : kept) {
        writeAttribute(info, nested);
    }
    return {codeAttribute.nameIndex, std::move(info)};
}

void copyMembers(Reader& reader, std::vector<std::uint8_t>& output,
                 const std::vector<std::string>& utf8, bool methods) {
    std::uint16_t count = reader.u2();
    writeU2(output, count);
    for (std::uint16_t i = 0; i < count; ++i) {
        append(output, reader.take(6));
        std::uint16_t attributeCount = reader.u2();
        std::vector<Attribute> attributes;
        attributes.reserve(attributeCount);
        for (std::uint16_t j = 0; j < attributeCount; ++j) {
            Attribute attribute = readAttribute(reader);
            if (methods && constantName(utf8, attribute.nameIndex) == "Code") {
                attribute = transformCode(attribute, utf8);
            }
            attributes.push_back(std::move(attribute));
        }
        writeU2(output, static_cast<std::uint16_t>(attributes.size()));
        for (const Attribute& attribute : attributes) {
            writeAttribute(output, attribute);
        }
    }
}

std::vector<std::uint8_t> transform(const std::vector<std::uint8_t>& input) {
    Reader reader(input);
    if (reader.u4() != 0xCAFEBABEU) {
        throw std::runtime_error("invalid class file magic");
    }
    reader.skip(4); // minor_version and major_version
    std::uint16_t poolCount = reader.u2();
    std::vector<std::string> utf8(poolCount);
    for (std::uint16_t i = 1; i < poolCount; ++i) {
        std::uint8_t tag = reader.u1();
        switch (tag) {
            case 1: {
                std::uint16_t length = reader.u2();
                auto bytes = reader.take(length);
                utf8[i] = std::string(bytes.begin(), bytes.end());
                break;
            }
            case 3:
            case 4:
                reader.skip(4);
                break;
            case 5:
            case 6:
                reader.skip(8);
                ++i;
                break;
            case 7:
            case 8:
            case 16:
            case 19:
            case 20:
                reader.skip(2);
                break;
            case 9:
            case 10:
            case 11:
            case 12:
            case 17:
            case 18:
                reader.skip(4);
                break;
            case 15:
                reader.skip(3);
                break;
            default:
                throw std::runtime_error("unsupported constant pool tag");
        }
    }

    std::vector<std::uint8_t> output(input.begin(),
                                     input.begin() + static_cast<std::ptrdiff_t>(reader.position()));
    append(output, reader.take(6)); // access_flags, this_class, super_class
    std::uint16_t interfaceCount = reader.u2();
    writeU2(output, interfaceCount);
    append(output, reader.take(static_cast<std::size_t>(interfaceCount) * 2U));

    copyMembers(reader, output, utf8, false);
    copyMembers(reader, output, utf8, true);

    std::uint16_t classAttributeCount = reader.u2();
    std::vector<Attribute> kept;
    const std::unordered_set<std::string> classDebugAttributes{"SourceFile", "SourceDebugExtension"};
    for (std::uint16_t i = 0; i < classAttributeCount; ++i) {
        Attribute attribute = readAttribute(reader);
        if (classDebugAttributes.count(constantName(utf8, attribute.nameIndex)) == 0) {
            kept.push_back(std::move(attribute));
        }
    }
    if (reader.remaining() != 0) {
        throw std::runtime_error("trailing class file data");
    }
    writeU2(output, static_cast<std::uint16_t>(kept.size()));
    for (const Attribute& attribute : kept) {
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
