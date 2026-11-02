// SPDX-License-Identifier: MIT
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace chinese_ime_lm {
// Promote only when left context strengthens the alternative's advantage over
// the leader and meaningfully boosts the alternative itself (at least 1 nat per
// character). Subtracting any positive prior advantage over the leader prevents
// a generally frequent word from winning solely on its unconditioned prior.
constexpr float kMinContextualBoost = 1.0f;

inline size_t contextualBest(const std::vector<float> &conditioned,
                             const std::vector<float> &unconditioned,
                             const std::vector<uint8_t> &facts) {
    if (conditioned.empty() || unconditioned.empty() || facts.empty()) return 0;
    size_t selected = 0;
    float bestScore = std::max(conditioned[0], unconditioned[0]);
    for (size_t index = 1; index < facts.size(); ++index) {
        if (!(facts[index] & 1) || conditioned[index] <= conditioned[selected]) continue;
        const float selfBoost = conditioned[index] - unconditioned[index];
        const float priorAdvantage = unconditioned[index] - unconditioned[0];
        const float calibrated = conditioned[index] - std::max(0.0f, priorAdvantage);
        if (selfBoost >= kMinContextualBoost && calibrated > bestScore) {
            selected = index;
            bestScore = calibrated;
        }
    }
    return selected;
}
} // namespace chinese_ime_lm
