// ----------------------------------------------------------------------------
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>
#include <cstdio>
#include <string>
#include <vector>

namespace {

bool WideFromUtf8(const std::string& s, std::wstring& out) {
    if (s.empty()) {
        return false;
    }
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) {
        return false;
    }
    out.assign(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), n);
    return true;
}

bool Sha256File(const std::wstring& path, std::string& hex) {
    hex.clear();
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        return false;
    }

    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    bool ok = false;
    unsigned char digest[32];
    std::vector<unsigned char> buf(1 << 20);

    if (BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0))
        && BCRYPT_SUCCESS(BCryptCreateHash(alg, &hash, nullptr, 0, nullptr, 0, 0))) {
        DWORD read = 0;
        bool ioOk = true;
        while (true) {
            if (!ReadFile(f, buf.data(), static_cast<DWORD>(buf.size()), &read, nullptr)) {
                ioOk = false;
                break;
            }
            if (read == 0) {
                break;
            }
            if (!BCRYPT_SUCCESS(BCryptHashData(hash, buf.data(), read, 0))) {
                ioOk = false;
                break;
            }
        }
        if (ioOk && BCRYPT_SUCCESS(BCryptFinishHash(hash, digest, sizeof(digest), 0))) {
            static const char* kDigits = "0123456789abcdef";
            for (unsigned char b : digest) {
                hex.push_back(kDigits[b >> 4]);
                hex.push_back(kDigits[b & 0x0F]);
            }
            ok = true;
        }
    }

    if (hash) {
        BCryptDestroyHash(hash);
    }
    if (alg) {
        BCryptCloseAlgorithmProvider(alg, 0);
    }
    CloseHandle(f);
    return ok;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <file> [<file> ...]\n", argv[0]);
        return 2;
    }
    int bad = 0;
    for (int i = 1; i < argc; ++i) {
        std::wstring path;
        std::string hex;
        if (!WideFromUtf8(argv[i], path) || !Sha256File(path, hex)) {
            std::fprintf(stderr, "sha256 failed: %s\n", argv[i]);
            ++bad;
            continue;
        }
        std::printf("%s  %s\n", hex.c_str(), argv[i]);
    }
    return bad == 0 ? 0 : 1;
}