// Invariant self-test for the Specter GLES extension-list spoof logic in main.cpp (build_gl_extensions +
// finalize_gl_extensions). Those functions are coupled to Zygisk globals/EGL, so this mirrors the pure
// ALGORITHM here (same splitmix64 seed, same 70%/80% gates, same dedup/vendor-gating/intersect/shuffle)
// with trimmed pools — the DATA (the extension arrays) is not what breaks; the algorithm is. If you change
// the algorithm in main.cpp, mirror it here. Cross-compile for arm64 + run on any device:
//   run-zygisk-tests.sh style — see dev-scripts/run-gl-ext-test.sh. Verifies the invariants that matter:
//   1. count == list size (glGetIntegerv(GL_NUM_EXTENSIONS) must equal what glGetStringi can index)
//   2. determinism: same android_id -> identical list every run
//   3. variation: two different android_ids -> different lists (the whole point — split the fingerprint)
//   4. subset-of-real: after intersecting with a "real driver" list, output ⊆ real (no over-advertise)
//   5. no duplicates; CORE always present; vendor-family gated on known vendor only
#include <cstdint>
#include <string>
#include <vector>
#include <set>
#include <cstdio>
#include <cassert>

static uint64_t g_rng = 0;
static uint64_t nxt() {
    uint64_t z = (g_rng += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}
static const char *CORE[] = {"GL_OES_EGL_image","GL_OES_EGL_sync","GL_OES_rgb8_rgba8","GL_KHR_debug",
    "GL_EXT_color_buffer_float","GL_ANDROID_extension_pack_es31a"};
static const char *OPT[]  = {"GL_KHR_texture_compression_astc_hdr","GL_EXT_shader_io_blocks",
    "GL_OES_sample_shading","GL_EXT_buffer_storage","GL_EXT_clip_control","GL_OES_texture_view",
    "GL_EXT_float_blend","GL_OES_copy_image","GL_EXT_draw_elements_base_vertex","GL_OVR_multiview"};
static const char *QCOM[] = {"GL_QCOM_alpha_test","GL_QCOM_tiled_rendering","GL_QCOM_motion_estimation"};
static const char *ARM[]  = {"GL_ARM_shader_framebuffer_fetch","GL_ARM_mali_shader_binary"};

static std::vector<std::string> build(const std::string &seed, const std::string &vendor) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : seed) { h ^= c; h *= 1099511628211ULL; }
    g_rng = h ? h : 0x1234567890abcdefULL;
    std::set<std::string> seen; std::vector<std::string> cand;
    auto add = [&](const char *e){ if (seen.insert(e).second) cand.emplace_back(e); };
    for (auto e : CORE) add(e);
    for (auto e : OPT) if ((nxt()%100)<70) add(e);
    bool arm = vendor.find("ARM")!=std::string::npos || vendor.find("Mali")!=std::string::npos;
    bool qc  = vendor.find("Qualcomm")!=std::string::npos || vendor.find("Adreno")!=std::string::npos;
    const char **fam = arm?ARM:(qc?QCOM:nullptr);
    size_t fn = arm?(sizeof(ARM)/sizeof(*ARM)):(qc?(sizeof(QCOM)/sizeof(*QCOM)):0);
    for (size_t i=0;i<fn;i++) if ((nxt()%100)<80) add(fam[i]);
    return cand;
}
// finalize: intersect with real, shuffle. Returns final list; count invariant = list.size().
static std::vector<std::string> finalize(std::vector<std::string> cand, const std::set<std::string> &real) {
    std::vector<std::string> out;
    for (auto &e : cand) if (real.empty() || real.count(e)) out.push_back(e);
    if (out.empty()) out = cand;
    for (size_t i=out.size(); i>1; i--) { size_t j = nxt()%i; std::swap(out[i-1], out[j]); }
    return out;
}

int fails = 0;
void chk(bool c, const char *m){ if(!c){ printf("FAIL: %s\n", m); fails++; } }

int main() {
    // 1. determinism
    auto a1 = build("androididAAA","Qualcomm"); auto a2 = build("androididAAA","Qualcomm");
    chk(a1==a2, "deterministic: same seed -> same candidate list");
    // 2. variation
    auto b = build("androididBBB","Qualcomm");
    chk(a1!=b, "variation: different seeds -> different lists");
    // 3. CORE always present, no dups
    std::set<std::string> s(a1.begin(), a1.end());
    chk(s.size()==a1.size(), "no duplicate extensions");
    for (auto e : CORE) chk(s.count(e), "CORE extension always present");
    // 4. vendor gating: unknown vendor gets NO family markers
    auto u = build("seedU","PowerVR");
    std::set<std::string> us(u.begin(), u.end());
    for (auto e : QCOM) chk(!us.count(e), "unknown vendor: no QCOM marker");
    for (auto e : ARM)  chk(!us.count(e), "unknown vendor: no ARM marker");
    // ARM vendor gets ARM not QCOM
    auto ar = build("seedAR","ARM Mali-G72"); std::set<std::string> ars(ar.begin(),ar.end());
    for (auto e : QCOM) chk(!ars.count(e), "ARM vendor: no QCOM marker");
    // 5. finalize subset-of-real + count invariant
    std::set<std::string> real;   // a "real driver" that supports only a subset
    real.insert("GL_OES_EGL_image"); real.insert("GL_KHR_debug"); real.insert("GL_EXT_buffer_storage");
    real.insert("GL_QCOM_alpha_test");
    auto fin = finalize(a1, real);
    for (auto &e : fin) chk(real.count(e), "finalized list is a strict subset of the real driver list");
    chk(!fin.empty(), "finalized list non-empty");
    // count invariant: whatever count we'd report == fin.size(), and indices 0..size-1 are valid
    chk(fin.size() == std::set<std::string>(fin.begin(),fin.end()).size(), "finalized list has no dups (count == unique)");
    // 6. empty-real fallback keeps candidates (never empties)
    auto fin2 = finalize(a1, {});
    chk(fin2.size()==a1.size(), "empty real list -> keep all candidates (never empty)");

    if (fails==0) { printf("ALL PASS (gl_ext_logic)\n"); return 0; }
    printf("%d FAILURE(S)\n", fails); return 1;
}
