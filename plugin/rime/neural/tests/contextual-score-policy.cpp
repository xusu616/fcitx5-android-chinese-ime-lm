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
}
