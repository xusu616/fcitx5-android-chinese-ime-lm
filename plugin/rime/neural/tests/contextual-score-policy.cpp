// SPDX-License-Identifier: MIT
#include "../contextual-score-policy.h"
#include <cassert>

int main() {
    // Measured with the released 4M weights: [派对, 排队]. The word prior
    // already favors 排队; only 还在 strengthens that preference.
    const std::vector<float> prior{-6.00464f, -5.27935f};
    assert(chinese_ime_lm::contextualBest({-6.11978f, -4.27005f}, prior, {1, 1}) == 1);
    assert(chinese_ime_lm::contextualBest({-5.872f, -5.42753f}, prior, {1, 1}) == 0);
    assert(chinese_ime_lm::contextualBest({-5.44313f, -5.23212f}, prior, {1, 1}) == 0);
    // A different spelling path remains ineligible even when the model likes it.
    assert(chinese_ime_lm::contextualBest({-6.11978f, -4.27005f}, prior, {1, 0}) == 0);
    // Measured with the released 4M weights: [直接, 之间] under 重庆和成都 vs 可以.
    const std::vector<float> zhijPrior{-3.85376f, -4.93374f};
    assert(chinese_ime_lm::contextualBest({-5.27076f, -3.85309f}, zhijPrior, {1, 1}) == 1);
    assert(chinese_ime_lm::contextualBest({-2.83241f, -5.53556f}, zhijPrior, {1, 1}) == 0);
    // Measured with the released 4M weights: [编译, 贬义, 变圆, 边缘, 便于] after 交叉.
    // A high-prior word (边缘) with a weak contextual shift must not displace 编译.
    const std::vector<float> bianyPrior{-7.69442f, -7.62064f, -9.10605f, -5.61013f, -6.00559f};
    assert(chinese_ime_lm::contextualBest(
        {-8.06628f, -7.02540f, -7.79635f, -5.40801f, -6.39396f},
        bianyPrior, {1, 1, 1, 1, 1}) == 0);
    // Measured: 避免 + xie, dictionary order [写, 协, 些, 血]. Single characters are
    // scored only by P(char | context); the 4M weights rank 些 above 写 here, and
    // the fine-tuned test weights rank 协 above it. Neither may displace 写.
    const std::vector<float> xiePrior{-8.06539f, -9.56922f, -9.14687f, -9.09443f};
    const std::vector<float> xieBase{-9.65381f, -8.56163f, -7.60871f, -8.3547f};
    assert(chinese_ime_lm::contextualBest(xieBase, xiePrior, {1, 1, 1, 1}) == 2);  // old behaviour
    assert(chinese_ime_lm::contextualBest(xieBase, xiePrior, {1, 1, 1, 1}, true) == 0);
    const std::vector<float> xieFtPrior{-9.03529f, -9.64827f, -10.8342f, -10.97835f};
    const std::vector<float> xieFt{-8.20233f, -7.89935f, -7.95265f, -8.57148f};
    assert(chinese_ime_lm::contextualBest(xieFt, xieFtPrior, {1, 1, 1, 1}, true) == 0);
    // A clear single-character correction still works: 还 + shi, [事, 是].
    assert(chinese_ime_lm::contextualBest({-10.14879f, -1.20265f},
        {-7.65448f, -4.54934f}, {1, 1}, true) == 1);
    const std::vector<const char *> one{"\xE5\x86\x99", "\xE5\x8D\x8F"};
    const std::vector<const char *> mixed{"\xE5\x86\x99", "\xE7\xBC\x96\xE8\xAF\x91"};
    assert(chinese_ime_lm::allSingleCharacters(one));
    assert(!chinese_ime_lm::allSingleCharacters(mixed));
    assert(!chinese_ime_lm::allSingleCharacters({}));
}
