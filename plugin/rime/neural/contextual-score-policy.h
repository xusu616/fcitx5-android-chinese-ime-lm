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
// A single character is scored only by P(character | context), which says
// nothing about the word it starts. With the 4M and 25M models this is noisy:
// after 避免 the homophones of xie score 些/协/血 above 写 by 1-2 nats. Real
// single-character corrections measured so far (还+shi→是, 请问+wen→问) beat the
// leader by 3.4 nats or more, so require a clear margin over the leader.
constexpr float kMinSingleCharacterAdvantage = 2.5f;

// True when every text is exactly one Unicode code point.
inline bool allSingleCharacters(const std::vector<const char *> &texts) {
    if (texts.empty()) return false;
    for (const char *text : texts) {
        if (!text || !*text) return false;
        size_t points = 0;
        for (const char *cursor = text; *cursor; ++cursor)
            if ((static_cast<unsigned char>(*cursor) & 0xC0) != 0x80) ++points;
        if (points != 1) return false;
    }
    return true;
}

inline size_t contextualBest(const std::vector<float> &conditioned,
                             const std::vector<float> &unconditioned,
                             const std::vector<uint8_t> &facts,
                             bool singleCharacters = false) {
    if (conditioned.empty() || unconditioned.empty() || facts.empty()) return 0;
    size_t selected = 0;
    float bestScore = std::max(conditioned[0], unconditioned[0]);
    for (size_t index = 1; index < facts.size(); ++index) {
        if (!(facts[index] & 1) || conditioned[index] <= conditioned[selected]) continue;
        const float selfBoost = conditioned[index] - unconditioned[index];
        const float priorAdvantage = unconditioned[index] - unconditioned[0];
        const float calibrated = conditioned[index] - std::max(0.0f, priorAdvantage);
        if (singleCharacters &&
            conditioned[index] - conditioned[0] < kMinSingleCharacterAdvantage) continue;
        if (selfBoost >= kMinContextualBoost && calibrated > bestScore) {
            selected = index;
            bestScore = calibrated;
        }
    }
    return selected;
}
} // namespace chinese_ime_lm
